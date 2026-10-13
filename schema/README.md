# The DAP schema

`debugAdapterProtocol.json` is Microsoft's JSON schema of the Debug Adapter Protocol, from
<https://github.com/microsoft/debug-adapter-protocol> (`debugAdapterProtocol.json` on `main`),
taken 2026-10-13: protocol version 1.71.x. It is published under the MIT License by Microsoft.

The classes in `us.bringardner.parley.dap.protocol` are generated from it. To move to a newer
version of the protocol, replace the file and regenerate:

```sh
mvn test-compile
java -cp target/classes:target/test-classes us.bringardner.parley.dap.generator.DapGenerator \
     schema/debugAdapterProtocol.json src/main/java
```

`TestGenerated` fails if the generated classes and the schema don't match.
