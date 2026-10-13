# parley-dap

The [Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/) (DAP) for Java,
both ends: write a debug adapter that VS Code, Eclipse (LSP4E), vim and other DAP clients can
drive, or a client that drives one.

- Every request, response, event and type of DAP 1.71 as a typed class, generated from
  Microsoft's official schema (and regenerated when the protocol changes)
- Connections over any pair of streams (a socket, or stdin/stdout, which is how IDEs usually
  start an adapter), or over parley-net's non-blocking server and client
- Its own small JSON reader and writer: no dependencies outside Parley
- Java 11 or later; Apache License 2.0

parley-dap is part of **Parley**, a family of Java libraries for implementing internet
protocols.

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-dap</artifactId>
    <version>1.0.0</version>
</dependency>
```

## An adapter

Implement `IDebugAdapter`: every request is a method with a default that answers
"notSupported", so only the ones the adapter supports are written. The adapter talks back
(events, reverse requests) through the `IDebugClient` it's given.

```java
class MyAdapter implements IDebugAdapter {
    private final IDebugClient client;

    MyAdapter(IDebugClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        // the initialized event must follow the initialize response
        DapEndpoint.afterResponse(client::initialized);
        return CompletableFuture.completedFuture(new Capabilities()
                .setSupportsConfigurationDoneRequest(true)
                .setSupportsConditionalBreakpoints(true));
    }

    @Override
    public CompletableFuture<SetBreakpointsResponseBody> setBreakpoints(SetBreakpointsArguments args) {
        List<Breakpoint> set = ...;
        return CompletableFuture.completedFuture(new SetBreakpointsResponseBody().setBreakpoints(set));
    }

    void hitBreakpoint(int id) {
        client.stopped(new StoppedEventBody()
                .setReason(StoppedEventBody.Reason.BREAKPOINT)
                .setThreadId(1)
                .setHitBreakpointIds(List.of(id)));
    }
}

// on a socket
Socket s = serverSocket.accept();
DapConnection c = DapConnection.adapter(MyAdapter::new, s.getInputStream(), s.getOutputStream());

// or as a process an IDE starts (it talks on stdin and stdout)
DapConnection.adapter(MyAdapter::new, System.in, System.out).closed().join();
```

A request fails, with an error response, when its method throws a `DapException` or returns a
future that fails with one. `DapException.forUser("...")` also sets the structured error a
client shows to the user.

## A client

```java
class Events implements IDebugClient {
    @Override
    public void stopped(StoppedEventBody body) {
        System.out.println("stopped: "+body.getReason());
    }
}

Socket s = new Socket("localhost", 4711);
DapConnection c = DapConnection.client(new Events(), s.getInputStream(), s.getOutputStream());
IDebugAdapter adapter = c.getAdapter();
Capabilities caps = adapter.initialize(new InitializeRequestArguments().setAdapterID("x")).get();
adapter.setBreakpoints(new SetBreakpointsArguments()
        .setSource(new Source().setPath("/home/me/job.sh"))
        .setBreakpoints(List.of(new SourceBreakpoint().setLine(12)))).get();
```

An error response fails the future with a `DapException` (its `getText()` is the error to show).

## Non-blocking (parley-net NIO)

```java
NioServer server = new NioServer(4711, "dap");
server.setDecoderFactory(ContentLengthFrameDecoder::new);
server.setHandlerFactory(DapNioHandler.adapters(MyAdapter::new));
server.setHandlerExecutor(Executors.newCachedThreadPool());   // if the adapter blocks
server.startAndWait(5000);
```

and on the client side `DapNioHandler.client(events)` with `NioClient.connectAndWait(...)`.
The two transports talk to each other, and to other DAP implementations (parley-dap was tried
against both ends of Eclipse's lsp4j).

## The classes

| | |
|---|---|
| `protocol.*Arguments`, `*ResponseBody`, `*EventBody`, `Capabilities`, `Source`, ... | DAP's types: getters, chainable setters, `toJson()` / `fromJson()`, `equals`, and `toString()` as JSON. Members DAP doesn't define (a launch configuration's own, say) are kept in `getExtra()`. |
| `protocol.StoppedEventBody.Reason`, `SteppingGranularity`, ... | The values of DAP's enumerations, as string constants. |
| `IDebugAdapter`, `IDebugClient` | The two ends. `otherRequest` and `otherEvent` carry anything DAP doesn't define. |
| `DapConnection` | A conversation over streams, with its own reader thread. |
| `DapEndpoint` | The protocol itself, for any transport: sequence numbers, matching responses to requests, dispatching. |
| `DapFraming` | The `Content-Length` framing on streams. |
| `nio.DapNioHandler`, `nio.ContentLengthFrameDecoder` | The same on parley-net's non-blocking connections. |
| `json.Json` | JSON to and from `Map` / `List` / `String` / `Long` / `Double` / `Boolean`. |

**Threads.** Incoming messages are handled one at a time, in order, on the connection's reader
thread (or a handler executor). A handler returns a future, so it needn't block; it must not
wait there for the answer to a request of its own, which can only arrive on the same thread.

## Regenerating

The protocol classes are generated from `schema/debugAdapterProtocol.json` by
`DapGenerator` (in the test sources) and kept in git. See [schema/README.md](schema/README.md).

## License

Apache License 2.0. See [LICENSE](LICENSE). The DAP schema in `schema/` is Microsoft's, under
the MIT License.
