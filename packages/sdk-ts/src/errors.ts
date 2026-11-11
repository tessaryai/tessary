// SPDX-License-Identifier: Apache-2.0
// The three failures a caller can tell apart, under one base class.

/** Base class: catch this to handle every SDK failure. */
export class TessaryPromptsError extends Error {
  constructor(message: string) {
    super(message);
    this.name = new.target.name;
  }
}

/** The server answered 404: no prompt with that name, version, or label. */
export class PromptNotFoundError extends TessaryPromptsError {
  readonly promptName: string;
  readonly version: number | null;
  readonly label: string | null;

  constructor(message: string, promptName: string, version: number | null, label: string | null) {
    super(message);
    this.promptName = promptName;
    this.version = version;
    this.label = label;
  }
}

/** The server refused the request: any non-2xx answer other than 404 and a redirect. */
export class PromptApiError extends TessaryPromptsError {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(`${status} ${code}: ${message}`);
    this.status = status;
    this.code = code;
  }
}

/** No usable answer: a timeout, a connection failure, a redirect, or an unreadable body. */
export class PromptTransportError extends TessaryPromptsError {}
