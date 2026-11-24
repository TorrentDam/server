# Build/Test Commands

Mill and the JDK come from the Nix dev shell (`nix develop`, or direnv via
`.envrc`). There is no `./mill` wrapper script; use the `mill` on `PATH`.

```bash
# Compile all modules
mill __.compile

# Run the server
mill server.run

# Build executable server JAR
mill server.assembly

# Format code with Scalafmt
mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
```

## Project Structure

- **build.mill**: Mill build definition (primary)
- **protocol/**: Protocol message types shared with the web client
- **server/**: HTTP server entry point

Each module has `src/main/scala/`
