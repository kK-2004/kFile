package contentcentersdk

import "net/http"

const DefaultPartSize int64 = 5 * 1024 * 1024

type ClientConfig struct {
	BaseURL    string
	AppToken   string
	HTTPClient *http.Client
}

type UploadOptions struct {
	Source      string
	Path        string
	ContentType string
}

type MultipartOptions struct {
	Source        string
	Path          string
	ContentType   string
	PartSizeBytes int64
}

type UploadInit struct {
	StorageKey string `json:"storageKey"`
	Source     string `json:"source"`
	PutURL     string `json:"putUrl"`
	ExpiresIn  int64  `json:"expiresIn"`
	FileID     int64  `json:"fileId"`
}

type UploadResult struct {
	FileID      int64
	Name        string
	Size        int64
	ContentType string
	StorageKey  string
	Source      string
}

type UploadedPart struct {
	PartNumber int    `json:"partNumber"`
	ETag       string `json:"etag"`
}

type MultipartInit struct {
	UploadID       string         `json:"uploadId"`
	ChunkKeyPrefix string         `json:"chunkKeyPrefix"`
	StorageKey     string         `json:"storageKey"`
	TotalChunks    int            `json:"totalChunks"`
	FileID         int64          `json:"fileId"`
	UploadedParts  []UploadedPart `json:"uploadedParts"`
	AlreadyDone    bool           `json:"alreadyDone"`
}

type MultipartPart struct {
	ChunkID int    `json:"chunkId"`
	ETag    string `json:"etag"`
}

type MultipartComplete struct {
	StorageKey string `json:"storageKey"`
	FileID     int64  `json:"fileId"`
	Size       int64  `json:"size"`
}

type DownloadLinkRequest struct {
	FileID    *int64  `json:"fileId,omitempty"`
	Key       *string `json:"key,omitempty"`
	Source    *string `json:"source,omitempty"`
	Filename  *string `json:"filename,omitempty"`
	ExpiresIn *int64  `json:"expiresIn,omitempty"`
}

func DownloadLinkByFileID(fileID int64) DownloadLinkRequest {
	return DownloadLinkRequest{FileID: &fileID}
}

func DownloadLinkByKey(key, source string) DownloadLinkRequest {
	return DownloadLinkRequest{Key: &key, Source: &source}
}

type DownloadLink struct {
	URL       string `json:"url"`
	ExpiresIn int64  `json:"expiresIn"`
}

type CDNLinkRequest struct {
	FileID    int64  `json:"fileId"`
	ExpiresIn *int64 `json:"expiresIn,omitempty"`
}

func CDNLinkByFileID(fileID int64) CDNLinkRequest {
	return CDNLinkRequest{FileID: fileID}
}

type CDNLink struct {
	URL         string `json:"url"`
	ExpiresIn   int64  `json:"expiresIn"`
	Permanent   bool   `json:"permanent"`
	ContentType string `json:"contentType"`
}
