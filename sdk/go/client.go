package contentcentersdk

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
)

type Client struct {
	baseURL  string
	appToken string
	http     *http.Client
}

func NewClient(config ClientConfig) (*Client, error) {
	baseURL := strings.TrimRight(strings.TrimSpace(config.BaseURL), "/")
	if baseURL == "" {
		return nil, &ContentCenterError{Status: -1, Message: "BaseURL cannot be empty"}
	}
	if strings.TrimSpace(config.AppToken) == "" {
		return nil, &ContentCenterError{Status: -1, Message: "AppToken cannot be empty"}
	}
	httpClient := config.HTTPClient
	if httpClient == nil {
		httpClient = &http.Client{}
	}
	return &Client{baseURL: baseURL, appToken: strings.TrimSpace(config.AppToken), http: httpClient}, nil
}

func (c *Client) postJSON(ctx context.Context, path string, requestBody any, responseBody any) error {
	if ctx == nil {
		ctx = context.Background()
	}
	payload, err := json.Marshal(requestBody)
	if err != nil {
		return &ContentCenterError{Status: -1, Message: "failed to encode request: " + err.Error(), Err: err}
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(payload))
	if err != nil {
		return &ContentCenterError{Status: -1, Message: "failed to create request: " + err.Error(), Err: err}
	}
	request.Header.Set("Authorization", "Bearer "+c.appToken)
	request.Header.Set("Content-Type", "application/json")
	response, err := c.http.Do(request)
	if err != nil {
		return &ContentCenterError{Status: -1, Message: "network request failed: " + err.Error(), Err: err}
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		return &ContentCenterError{Status: -1, Message: "failed to read response: " + err.Error(), Err: err}
	}
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return apiResponseError(response.StatusCode, body)
	}
	if responseBody == nil || len(bytes.TrimSpace(body)) == 0 {
		return nil
	}
	if err := json.Unmarshal(body, responseBody); err != nil {
		return &ContentCenterError{Status: -1, Message: "failed to decode response: " + err.Error(), Err: err}
	}
	return nil
}

func apiResponseError(status int, body []byte) error {
	var apiError struct {
		Message string `json:"message"`
	}
	_ = json.Unmarshal(body, &apiError)
	message := strings.TrimSpace(apiError.Message)
	if message == "" {
		if status == http.StatusUnauthorized {
			message = fmt.Sprintf("appToken 无效、已轮换或应用被禁用 (HTTP %d)", status)
		} else {
			message = fmt.Sprintf("请求失败 (HTTP %d)", status)
		}
	} else if status == http.StatusUnauthorized {
		message += "（appToken 可能已轮换或应用被禁用）"
	}
	return &ContentCenterError{Status: status, Message: message}
}

func (c *Client) InitUpload(ctx context.Context, filename string, size *int64, options UploadOptions) (UploadInit, error) {
	body := map[string]any{"originalName": filename}
	if size != nil {
		body["size"] = *size
	}
	addUploadOptions(body, options)
	var result UploadInit
	if err := c.postJSON(ctx, "/api/open/uploads", body, &result); err != nil {
		return UploadInit{}, err
	}
	return result, nil
}

func addUploadOptions(body map[string]any, options UploadOptions) {
	if options.ContentType != "" {
		body["contentType"] = options.ContentType
	}
	if options.Path != "" {
		body["path"] = options.Path
	}
	if options.Source != "" {
		body["source"] = options.Source
	}
}

func (c *Client) putPresigned(ctx context.Context, targetURL string, body io.Reader, contentType string) (string, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPut, targetURL, body)
	if err != nil {
		return "", &ContentCenterError{Status: -1, Message: "failed to create presigned PUT request: " + err.Error(), Err: err}
	}
	request.Header.Set("Content-Type", contentType)
	response, err := c.http.Do(request)
	if err != nil {
		return "", &ContentCenterError{Status: -1, Message: "presigned PUT failed: " + err.Error(), Err: err}
	}
	defer response.Body.Close()
	responseBody, readErr := io.ReadAll(response.Body)
	if readErr != nil {
		return "", &ContentCenterError{Status: -1, Message: "failed to read presigned PUT response: " + readErr.Error(), Err: readErr}
	}
	if response.StatusCode < http.StatusOK || response.StatusCode >= http.StatusMultipleChoices {
		return "", apiResponseError(response.StatusCode, responseBody)
	}
	return strings.Trim(response.Header.Get("ETag"), `"`), nil
}

func (c *Client) CompleteUpload(ctx context.Context, storageKey, source string) (UploadResult, error) {
	body := map[string]any{"storageKey": storageKey, "source": source}
	var result UploadResult
	if err := c.postJSON(ctx, "/api/open/uploads/complete", body, &result); err != nil {
		return UploadResult{}, err
	}
	result.StorageKey = storageKey
	result.Source = source
	return result, nil
}

func (c *Client) UploadReader(ctx context.Context, reader io.Reader, filename string, size *int64, options UploadOptions) (UploadResult, error) {
	init, err := c.InitUpload(ctx, filename, size, options)
	if err != nil {
		return UploadResult{}, err
	}
	contentType := options.ContentType
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	if _, err := c.putPresigned(ctx, init.PutURL, reader, contentType); err != nil {
		return UploadResult{}, err
	}
	return c.CompleteUpload(ctx, init.StorageKey, init.Source)
}

func (c *Client) Upload(ctx context.Context, path string, options UploadOptions) (UploadResult, error) {
	file, err := os.Open(path)
	if err != nil {
		return UploadResult{}, &ContentCenterError{Status: -1, Message: "failed to open local file: " + err.Error(), Err: err}
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return UploadResult{}, &ContentCenterError{Status: -1, Message: "failed to stat local file: " + err.Error(), Err: err}
	}
	size := info.Size()
	return c.UploadReader(ctx, file, info.Name(), &size, options)
}

func (c *Client) InitMultipartUpload(ctx context.Context, filename string, fileSize int64, totalChunks int, contentMD5 string, options MultipartOptions) (MultipartInit, error) {
	body := map[string]any{
		"originalName": filename,
		"size":         fileSize,
		"totalChunks":  totalChunks,
		"contentMd5":   contentMD5,
	}
	addUploadOptions(body, UploadOptions{Source: options.Source, Path: options.Path, ContentType: options.ContentType})
	var result MultipartInit
	if err := c.postJSON(ctx, "/api/open/uploads/multipart/init", body, &result); err != nil {
		return MultipartInit{}, err
	}
	return result, nil
}

func (c *Client) SignMultipartPart(ctx context.Context, contentMD5 string, chunkID int) (string, error) {
	body := map[string]any{"contentMd5": contentMD5, "chunkId": chunkID}
	var result struct {
		URL string `json:"url"`
	}
	if err := c.postJSON(ctx, "/api/open/uploads/multipart/sign", body, &result); err != nil {
		return "", err
	}
	if strings.TrimSpace(result.URL) == "" {
		return "", &ContentCenterError{Status: -1, Message: "multipart sign response is missing url"}
	}
	return result.URL, nil
}

func (c *Client) CompleteMultipartUpload(ctx context.Context, contentMD5 string, parts []MultipartPart) (MultipartComplete, error) {
	if parts == nil {
		parts = []MultipartPart{}
	}
	body := map[string]any{"contentMd5": contentMD5, "parts": parts}
	var result MultipartComplete
	if err := c.postJSON(ctx, "/api/open/uploads/multipart/complete", body, &result); err != nil {
		return MultipartComplete{}, err
	}
	return result, nil
}

func (c *Client) UploadMultipart(ctx context.Context, path string, options MultipartOptions) (UploadResult, error) {
	file, err := os.Open(path)
	if err != nil {
		return UploadResult{}, &ContentCenterError{Status: -1, Message: "failed to open local file: " + err.Error(), Err: err}
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return UploadResult{}, &ContentCenterError{Status: -1, Message: "failed to stat local file: " + err.Error(), Err: err}
	}
	contentMD5, err := md5File(file)
	if err != nil {
		return UploadResult{}, &ContentCenterError{Status: -1, Message: "failed to calculate file MD5: " + err.Error(), Err: err}
	}
	partSize := options.PartSizeBytes
	if partSize < DefaultPartSize {
		partSize = DefaultPartSize
	}
	totalChunks := int((info.Size() + partSize - 1) / partSize)
	init, err := c.InitMultipartUpload(ctx, info.Name(), info.Size(), totalChunks, contentMD5, options)
	if err != nil {
		return UploadResult{}, err
	}
	if init.AlreadyDone {
		return UploadResult{
			FileID:      init.FileID,
			Name:        info.Name(),
			Size:        info.Size(),
			ContentType: options.ContentType,
			StorageKey:  init.StorageKey,
			Source:      options.Source,
		}, nil
	}

	etags := make(map[int]string, len(init.UploadedParts))
	for _, part := range init.UploadedParts {
		if part.PartNumber > 0 && strings.TrimSpace(part.ETag) != "" {
			etags[part.PartNumber] = strings.Trim(part.ETag, `"`)
		}
	}
	buffer := make([]byte, int(partSize))
	for chunkID := 0; chunkID < totalChunks; chunkID++ {
		partNumber := chunkID + 1
		if _, ok := etags[partNumber]; ok {
			continue
		}
		offset := int64(chunkID) * partSize
		read, readErr := file.ReadAt(buffer, offset)
		if readErr != nil && readErr != io.EOF {
			return UploadResult{}, &ContentCenterError{Status: -1, Message: "failed to read file part: " + readErr.Error(), Err: readErr}
		}
		if read == 0 {
			return UploadResult{}, &ContentCenterError{Status: -1, Message: fmt.Sprintf("file part %d is empty", partNumber)}
		}
		url, err := c.SignMultipartPart(ctx, contentMD5, chunkID)
		if err != nil {
			return UploadResult{}, err
		}
		etag, err := c.putPresigned(ctx, url, bytes.NewReader(buffer[:read]), "application/octet-stream")
		if err != nil {
			return UploadResult{}, err
		}
		if etag == "" {
			return UploadResult{}, &ContentCenterError{Status: -1, Message: fmt.Sprintf("multipart part %d response is missing ETag", partNumber)}
		}
		etags[partNumber] = etag
	}

	parts := make([]MultipartPart, totalChunks)
	for chunkID := 0; chunkID < totalChunks; chunkID++ {
		etag := etags[chunkID+1]
		if etag == "" {
			return UploadResult{}, &ContentCenterError{Status: -1, Message: fmt.Sprintf("missing ETag for multipart part %d", chunkID+1)}
		}
		parts[chunkID] = MultipartPart{ChunkID: chunkID, ETag: etag}
	}
	done, err := c.CompleteMultipartUpload(ctx, contentMD5, parts)
	if err != nil {
		return UploadResult{}, err
	}
	fileID := done.FileID
	if fileID == 0 {
		fileID = init.FileID
	}
	storageKey := done.StorageKey
	if storageKey == "" {
		storageKey = init.StorageKey
	}
	size := done.Size
	if size == 0 {
		size = info.Size()
	}
	return UploadResult{
		FileID:      fileID,
		Name:        info.Name(),
		Size:        size,
		ContentType: options.ContentType,
		StorageKey:  storageKey,
		Source:      options.Source,
	}, nil
}

func md5File(file *os.File) (string, error) {
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	hash := md5.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func (c *Client) GetDownloadLink(ctx context.Context, request DownloadLinkRequest) (DownloadLink, error) {
	var result DownloadLink
	if err := c.postJSON(ctx, "/api/open/download-links", request, &result); err != nil {
		return DownloadLink{}, err
	}
	return result, nil
}

func (c *Client) GetCDNLink(ctx context.Context, request CDNLinkRequest) (CDNLink, error) {
	var result CDNLink
	if err := c.postJSON(ctx, "/api/open/cdn-links", request, &result); err != nil {
		return CDNLink{}, err
	}
	return result, nil
}
