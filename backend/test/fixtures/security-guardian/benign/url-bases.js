// A router parsing a request line needs a base URL; nothing here goes to the network and none of it is cleartext.
export function pathOf(request) {
  return new URL(request.url ?? "/", "http://internal.invalid").pathname;
}

export function searchOf(request) {
  return new URL(request.url ?? "/", "http://listing.local").searchParams;
}

// Documentation and labels legitimately name hosts and schemes.
export const DOCS = "See https://example.com/docs for the public policy.";
export const LABEL = "http://localhost:4173 is the preview port";
