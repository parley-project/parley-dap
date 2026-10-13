# Changelog

## parley-dap 1.0.0 (unreleased)

First version.

- Typed classes for all of DAP 1.71 (requests, responses, events, types, enumerations),
  generated from Microsoft's schema by `DapGenerator`, which is kept with the tests;
  `TestGenerated` checks the generated sources are current.
- `IDebugAdapter` and `IDebugClient` with default methods, dispatchers and proxies.
- `DapEndpoint`: sequence numbers, request/response matching, error responses
  (`DapException`, with DAP's structured `Message`), `afterResponse` for the initialized event.
- `DapConnection` over streams (sockets, stdin/stdout); `DapNioHandler` and
  `ContentLengthFrameDecoder` for parley-net's `NioServer` and `NioClient`.
- `json.Json`: a JSON reader and writer with no dependencies.
