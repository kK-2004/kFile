package contentcentersdk

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestNewClientRejectsMissingConfiguration(t *testing.T) {
	if _, err := NewClient(ClientConfig{AppToken: "token"}); err == nil {
		t.Fatal("expected missing BaseURL to be rejected")
	}
	if _, err := NewClient(ClientConfig{BaseURL: "https://file.example"}); err == nil {
		t.Fatal("expected missing AppToken to be rejected")
	}
}

func TestAPIRequestInjectsBearerAndParsesAPIError(t *testing.T) {
	var authorization string
	var contentType string
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authorization = r.Header.Get("Authorization")
		contentType = r.Header.Get("Content-Type")
		if r.Method != http.MethodPost || r.URL.Path != "/api/open/uploads" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"message":"未知或未启用的数据源: minio"}`))
	}))
	defer server.Close()

	client, err := NewClient(ClientConfig{BaseURL: server.URL, AppToken: "kapp_test"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.InitUpload(context.Background(), "a.txt", nil, UploadOptions{})
	if err == nil {
		t.Fatal("expected API error")
	}
	var apiErr *ContentCenterError
	if !errors.As(err, &apiErr) {
		t.Fatalf("expected ContentCenterError, got %T", err)
	}
	if apiErr.Status != http.StatusBadRequest || apiErr.Message != "未知或未启用的数据源: minio" {
		t.Fatalf("unexpected error: %+v", apiErr)
	}
	if authorization != "Bearer kapp_test" {
		t.Fatalf("unexpected Authorization header: %q", authorization)
	}
	if contentType != "application/json" {
		t.Fatalf("unexpected Content-Type header: %q", contentType)
	}
}

func TestUnauthorizedErrorHintsTokenRotation(t *testing.T) {
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"message":"无权限，请登录"}`))
	}))
	defer server.Close()

	client, err := NewClient(ClientConfig{BaseURL: server.URL, AppToken: "kapp_test"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.InitUpload(context.Background(), "a.txt", nil, UploadOptions{})
	if err == nil || !strings.Contains(err.Error(), "轮换") {
		t.Fatalf("expected token rotation hint, got %v", err)
	}
}

func TestUploadReaderRunsThreeStepsAndKeepsBearerOffPresignedPut(t *testing.T) {
	var paths []string
	var putAuthorization string
	var putContentType string
	var putBody string
	var completeBody string
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		switch r.URL.Path {
		case "/api/open/uploads":
			if r.Header.Get("Authorization") != "Bearer kapp_test" {
				t.Errorf("unexpected init Authorization: %q", r.Header.Get("Authorization"))
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"storageKey":"k1","source":"oss","putUrl":"` + server.URL + `/put","expiresIn":600,"fileId":1}`))
		case "/put":
			putAuthorization = r.Header.Get("Authorization")
			putContentType = r.Header.Get("Content-Type")
			body, _ := io.ReadAll(r.Body)
			putBody = string(body)
			w.WriteHeader(http.StatusOK)
		case "/api/open/uploads/complete":
			body, _ := io.ReadAll(r.Body)
			completeBody = string(body)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"fileId":1,"name":"a.txt","size":3,"contentType":"text/plain"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewClient(ClientConfig{BaseURL: server.URL, AppToken: "kapp_test"})
	if err != nil {
		t.Fatal(err)
	}
	size := int64(3)
	result, err := client.UploadReader(context.Background(), strings.NewReader("abc"), "a.txt", &size,
		UploadOptions{Source: "oss", Path: "reports", ContentType: "text/plain"})
	if err != nil {
		t.Fatal(err)
	}
	if result.FileID != 1 || result.Size != 3 || result.StorageKey != "k1" || result.Source != "oss" {
		t.Fatalf("unexpected upload result: %+v", result)
	}
	if strings.Join(paths, ",") != "/api/open/uploads,/put,/api/open/uploads/complete" {
		t.Fatalf("unexpected request order: %v", paths)
	}
	if putAuthorization != "" {
		t.Fatalf("presigned PUT leaked Authorization: %q", putAuthorization)
	}
	if putContentType != "text/plain" || putBody != "abc" {
		t.Fatalf("unexpected PUT: contentType=%q body=%q", putContentType, putBody)
	}
	if !strings.Contains(completeBody, `"storageKey":"k1"`) || !strings.Contains(completeBody, `"source":"oss"`) {
		t.Fatalf("unexpected complete body: %s", completeBody)
	}
}

func TestUploadPutFailureDoesNotCallComplete(t *testing.T) {
	completeCalls := 0
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/open/uploads":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"storageKey":"k1","source":"oss","putUrl":"` + server.URL + `/put-fail","expiresIn":600,"fileId":1}`))
		case "/put-fail":
			w.WriteHeader(http.StatusForbidden)
		case "/api/open/uploads/complete":
			completeCalls++
			w.WriteHeader(http.StatusOK)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewClient(ClientConfig{BaseURL: server.URL, AppToken: "kapp_test"})
	if err != nil {
		t.Fatal(err)
	}
	size := int64(3)
	_, err = client.UploadReader(context.Background(), strings.NewReader("abc"), "a.txt", &size, UploadOptions{})
	if err == nil {
		t.Fatal("expected presigned PUT failure")
	}
	var apiErr *ContentCenterError
	if !errors.As(err, &apiErr) || apiErr.Status != http.StatusForbidden {
		t.Fatalf("unexpected error: %v", err)
	}
	if completeCalls != 0 {
		t.Fatalf("complete called %d times after PUT failure", completeCalls)
	}
}

func TestUploadMultipartResumesMissingPartsAndCompletesWithETags(t *testing.T) {
	data := append(bytes.Repeat([]byte("a"), int(DefaultPartSize)), []byte("xyz")...)
	dir := t.TempDir()
	path := filepath.Join(dir, "sample.bin")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	hash := md5.Sum(data)
	expectedMD5 := hex.EncodeToString(hash[:])
	var initBody string
	var completeBody string
	partUploads := 0
	var uploadedPart []byte
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/open/uploads/multipart/init":
			body, _ := io.ReadAll(r.Body)
			initBody = string(body)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"uploadId":"u","chunkKeyPrefix":"p","storageKey":"sk","totalChunks":2,"fileId":9,"uploadedParts":[{"partNumber":1,"etag":"e1"}],"alreadyDone":false}`))
		case "/api/open/uploads/multipart/sign":
			var request struct {
				ChunkID int `json:"chunkId"`
			}
			if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
				t.Errorf("decode sign request: %v", err)
			}
			if request.ChunkID != 1 {
				t.Errorf("expected only missing chunk 1 to be signed, got %d", request.ChunkID)
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"url":"` + server.URL + `/part"}`))
		case "/part":
			partUploads++
			uploadedPart, _ = io.ReadAll(r.Body)
			w.Header().Set("ETag", `"e2"`)
			w.WriteHeader(http.StatusOK)
		case "/api/open/uploads/multipart/complete":
			body, _ := io.ReadAll(r.Body)
			completeBody = string(body)
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"storageKey":"sk","fileId":9,"size":` + strconv.Itoa(len(data)) + `}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewClient(ClientConfig{BaseURL: server.URL, AppToken: "kapp_test"})
	if err != nil {
		t.Fatal(err)
	}
	result, err := client.UploadMultipart(context.Background(), path, MultipartOptions{Source: "minio"})
	if err != nil {
		t.Fatal(err)
	}
	if result.FileID != 9 || result.StorageKey != "sk" || result.Size != int64(len(data)) {
		t.Fatalf("unexpected multipart result: %+v", result)
	}
	if partUploads != 1 || !bytes.Equal(uploadedPart, []byte("xyz")) {
		t.Fatalf("unexpected part upload count/body: %d %q", partUploads, uploadedPart)
	}
	if !strings.Contains(initBody, `"contentMd5":"`+expectedMD5+`"`) || !strings.Contains(initBody, `"totalChunks":2`) {
		t.Fatalf("unexpected init body: %s", initBody)
	}
	if !strings.Contains(completeBody, `"chunkId":0`) || !strings.Contains(completeBody, `"etag":"e1"`) ||
		!strings.Contains(completeBody, `"chunkId":1`) || !strings.Contains(completeBody, `"etag":"e2"`) {
		t.Fatalf("unexpected complete body: %s", completeBody)
	}
}

func TestUploadMultipartAlreadyDoneSkipsSigningAndComplete(t *testing.T) {
	data := []byte("already uploaded")
	dir := t.TempDir()
	path := filepath.Join(dir, "done.bin")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	signCalls := 0
	completeCalls := 0
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/open/uploads/multipart/init":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"storageKey":"sk","totalChunks":1,"fileId":7,"alreadyDone":true}`))
		case "/api/open/uploads/multipart/sign":
			signCalls++
		case "/api/open/uploads/multipart/complete":
			completeCalls++
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewClient(ClientConfig{BaseURL: server.URL, AppToken: "kapp_test"})
	if err != nil {
		t.Fatal(err)
	}
	result, err := client.UploadMultipart(context.Background(), path, MultipartOptions{Source: "minio"})
	if err != nil {
		t.Fatal(err)
	}
	if result.FileID != 7 || result.Size != int64(len(data)) || result.StorageKey != "sk" {
		t.Fatalf("unexpected already-done result: %+v", result)
	}
	if signCalls != 0 || completeCalls != 0 {
		t.Fatalf("already-done upload made extra calls: sign=%d complete=%d", signCalls, completeCalls)
	}
}

func TestGetDownloadAndCDNLinksPreserveServerURLs(t *testing.T) {
	var downloadBody string
	var cdnBody string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/open/download-links":
			downloadBody = string(body)
			_, _ = w.Write([]byte(`{"url":"https://storage.example/download?a=1","expiresIn":300}`))
		case "/api/open/cdn-links":
			cdnBody = string(body)
			_, _ = w.Write([]byte(`{"url":"https://file.example/file/cdn/token","expiresIn":0,"permanent":true,"contentType":"image/png"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewClient(ClientConfig{BaseURL: server.URL, AppToken: "kapp_test"})
	if err != nil {
		t.Fatal(err)
	}
	filename := "report.pdf"
	expiresIn := int64(300)
	downloadRequest := DownloadLinkByFileID(9)
	downloadRequest.Filename = &filename
	downloadRequest.ExpiresIn = &expiresIn
	download, err := client.GetDownloadLink(context.Background(), downloadRequest)
	if err != nil {
		t.Fatal(err)
	}
	if download.URL != "https://storage.example/download?a=1" || download.ExpiresIn != 300 {
		t.Fatalf("unexpected download link: %+v", download)
	}
	if !strings.Contains(downloadBody, `"fileId":9`) || !strings.Contains(downloadBody, `"filename":"report.pdf"`) {
		t.Fatalf("unexpected download request: %s", downloadBody)
	}

	cdn, err := client.GetCDNLink(context.Background(), CDNLinkByFileID(9))
	if err != nil {
		t.Fatal(err)
	}
	if cdn.URL != "https://file.example/file/cdn/token" || !cdn.Permanent || cdn.ContentType != "image/png" {
		t.Fatalf("unexpected CDN link: %+v", cdn)
	}
	if !strings.Contains(cdnBody, `"fileId":9`) {
		t.Fatalf("unexpected CDN request: %s", cdnBody)
	}
}
