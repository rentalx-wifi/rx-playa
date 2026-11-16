package oauthbroker

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSoundCloudConfigHidesSecret(t *testing.T) {
	broker := NewSoundCloud("client", "super-secret", "rxmusic://oauth/soundcloud")
	recorder := httptest.NewRecorder()
	broker.ServeConfig(recorder, httptest.NewRequest(http.MethodGet, "/", nil))
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d", recorder.Code)
	}
	if strings.Contains(recorder.Body.String(), "super-secret") {
		t.Fatal("configuration response leaked the client secret")
	}
	var body map[string]interface{}
	if err := json.Unmarshal(recorder.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["configured"] != true || body["clientId"] != "client" {
		t.Fatalf("unexpected response: %#v", body)
	}
}

func TestSoundCloudRejectsMissingConfiguration(t *testing.T) {
	broker := NewSoundCloud("", "", "rxmusic://oauth/soundcloud")
	recorder := httptest.NewRecorder()
	broker.Exchange(recorder, httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{}`)))
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d", recorder.Code)
	}
}

func TestSoundCloudRejectsBadPKCEBeforeUpstream(t *testing.T) {
	broker := NewSoundCloud("client", "secret", "rxmusic://oauth/soundcloud")
	recorder := httptest.NewRecorder()
	broker.Exchange(recorder, httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"code":"authorization-code","codeVerifier":"short"}`)))
	if recorder.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d: %s", recorder.Code, recorder.Body.String())
	}
}

func TestSoundCloudRedirectMustBeExactAppCallback(t *testing.T) {
	for _, raw := range []string{
		"https://example.com/callback",
		"rxmusic://oauth/soundcloud?next=evil",
		"rxmusic://oauth/soundcloud/extra",
		"rxmusic://evil/soundcloud",
	} {
		if NewSoundCloud("client", "secret", raw).Configured() {
			t.Fatalf("accepted redirect %q", raw)
		}
	}
}
