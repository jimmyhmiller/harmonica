# Harmonica

A standards-compliant JavaScript parser written in Java. Produces ESTree-compatible ASTs validated against Acorn.

## Status

Work in progress.

## Features

- Full ES2024 support including modules, classes, async/await, and destructuring
- ESTree-compliant AST output
- Modern Java implementation using records, sealed interfaces, and pattern matching
- Validated against test262 and real-world JavaScript libraries

## Usage

```java
import com.jsparser.Parser;
import com.jsparser.ast.Program;

String source = "const add = (a, b) => a + b;";
Program ast = Parser.parse(source);
```

## Building

Requires Java 21+.

```bash
./mvnw test
```

## License

AGPL-3.0
