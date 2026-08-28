package contentcentersdk

import "fmt"

type ContentCenterError struct {
	Status  int
	Message string
	Err     error
}

func (e *ContentCenterError) Error() string {
	if e == nil {
		return "<nil>"
	}
	if e.Message != "" {
		return e.Message
	}
	if e.Err != nil {
		return e.Err.Error()
	}
	return fmt.Sprintf("content center request failed (status %d)", e.Status)
}

func (e *ContentCenterError) Unwrap() error {
	if e == nil {
		return nil
	}
	return e.Err
}
