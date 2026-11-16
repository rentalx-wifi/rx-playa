package oauthbroker

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// SoundCloud keeps the client secret on the server while the Android app owns
// PKCE's verifier and state. The broker never stores or logs either the
// authorization code or the returned account tokens.
type SoundCloud struct {
	ClientID     string
	ClientSecret string
	RedirectURI  string
	HTTP         *http.Client
	TokenURL     string
}

type tokenRequest struct {
	Code         string `json:"code"`
	CodeVerifier string `json:"codeVerifier"`
	RefreshToken string `json:"refreshToken"`
}

type tokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
	Scope        string `json:"scope"`
	TokenType    string `json:"token_type"`
}

func NewSoundCloud(clientID, clientSecret, redirectURI string) *SoundCloud {
	return &SoundCloud{
		ClientID:     strings.TrimSpace(clientID),
		ClientSecret: strings.TrimSpace(clientSecret),
		RedirectURI:  strings.TrimSpace(redirectURI),
		HTTP:         &http.Client{Timeout: 15 * time.Second},
		TokenURL:     "https://secure.soundcloud.com/oauth/token",
	}
}

func (s *SoundCloud) Configured() bool {
	return s != nil && s.ClientID != "" && s.ClientSecret != "" && validRedirect(s.RedirectURI)
}

func (s *SoundCloud) ServeConfig(w http.ResponseWriter, _ *http.Request) {
	configured := s.Configured()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"configured":  configured,
		"clientId":    valueWhen(configured, s.ClientID),
		"redirectUri": valueWhen(configured, s.RedirectURI),
	})
}

func (s *SoundCloud) Exchange(w http.ResponseWriter, r *http.Request) {
	if !s.requireConfigured(w) {
		return
	}
	var request tokenRequest
	if !decode(w, r, &request) {
		return
	}
	request.Code = strings.TrimSpace(request.Code)
	request.CodeVerifier = strings.TrimSpace(request.CodeVerifier)
	if len(request.Code) < 8 || len(request.Code) > 2048 || !validVerifier(request.CodeVerifier) {
		writeError(w, http.StatusUnprocessableEntity, "invalid_oauth_request", "Invalid authorization code or PKCE verifier.")
		return
	}
	s.forwardToken(w, url.Values{
		"grant_type":    {"authorization_code"},
		"client_id":     {s.ClientID},
		"client_secret": {s.ClientSecret},
		"redirect_uri":  {s.RedirectURI},
		"code_verifier": {request.CodeVerifier},
		"code":          {request.Code},
	})
}

func (s *SoundCloud) Refresh(w http.ResponseWriter, r *http.Request) {
	if !s.requireConfigured(w) {
		return
	}
	var request tokenRequest
	if !decode(w, r, &request) {
		return
	}
	request.RefreshToken = strings.TrimSpace(request.RefreshToken)
	if len(request.RefreshToken) < 16 || len(request.RefreshToken) > 4096 {
		writeError(w, http.StatusUnprocessableEntity, "invalid_refresh_token", "Invalid refresh token.")
		return
	}
	s.forwardToken(w, url.Values{
		"grant_type":    {"refresh_token"},
		"client_id":     {s.ClientID},
		"client_secret": {s.ClientSecret},
		"refresh_token": {request.RefreshToken},
	})
}

func (s *SoundCloud) requireConfigured(w http.ResponseWriter) bool {
	if s.Configured() {
		return true
	}
	writeError(w, http.StatusServiceUnavailable, "soundcloud_not_configured", "SoundCloud sign-in is not configured on this server.")
	return false
}

func (s *SoundCloud) forwardToken(w http.ResponseWriter, values url.Values) {
	request, err := http.NewRequest(http.MethodPost, s.TokenURL, strings.NewReader(values.Encode()))
	if err != nil {
		writeError(w, http.StatusBadGateway, "oauth_upstream_failed", "Could not contact SoundCloud.")
		return
	}
	request.Header.Set("Accept", "application/json; charset=utf-8")
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := s.HTTP.Do(request)
	if err != nil {
		writeError(w, http.StatusBadGateway, "oauth_upstream_failed", "Could not contact SoundCloud.")
		return
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		// Consume only a small bounded body so the connection can be reused. The
		// upstream body can contain request details and is deliberately not echoed.
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 8*1024))
		writeError(w, http.StatusBadGateway, "oauth_exchange_rejected", "SoundCloud rejected the authorization request.")
		return
	}
	var token tokenResponse
	decoder := json.NewDecoder(io.LimitReader(response.Body, 64*1024))
	if err := decoder.Decode(&token); err != nil || token.AccessToken == "" || token.RefreshToken == "" || token.ExpiresIn <= 0 {
		writeError(w, http.StatusBadGateway, "oauth_invalid_response", "SoundCloud returned an invalid token response.")
		return
	}
	writeJSON(w, http.StatusOK, token)
}

func validVerifier(value string) bool {
	if len(value) < 43 || len(value) > 128 {
		return false
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') || strings.ContainsRune("-._~", char) {
			continue
		}
		return false
	}
	return true
}

func validRedirect(raw string) bool {
	parsed, err := url.Parse(raw)
	return err == nil && parsed.Scheme == "rxmusic" && parsed.Host == "oauth" && parsed.Path == "/soundcloud" && parsed.RawQuery == "" && parsed.Fragment == ""
}

func decode(w http.ResponseWriter, r *http.Request, value interface{}) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 16*1024)
	defer r.Body.Close()
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(value); err != nil {
		writeError(w, http.StatusUnprocessableEntity, "invalid_json", "Malformed JSON body.")
		return false
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusUnprocessableEntity, "invalid_json", "Request body must contain one JSON object.")
		return false
	}
	return true
}

func valueWhen(enabled bool, value string) string {
	if enabled {
		return value
	}
	return ""
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{"error": code, "message": message})
}

func writeJSON(w http.ResponseWriter, status int, value interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
