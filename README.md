# bcbridge-maven-plugin

A Maven plugin for rewriting Java bytecode with Byte Buddy during the `package` phase.

The `rewrite` goal replaces configured source method bodies with direct calls to destination methods. Method
parameters are relayed to the destination. The packaged application does not need Byte Buddy, a Java agent, or
runtime reflection.

## Requirements

- Java 17 or newer
- Maven 3.9 or newer

## Install locally

Clone this repository and install the plugin in your local Maven repository:

```shell
mvn install
```

This makes version `1.0.0-SNAPSHOT` available to other Maven projects on the same machine.

## Add the plugin to a project

Add this configuration inside the consuming project's `<build>` element:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>br.com.isageek</groupId>
      <artifactId>bcbridge-maven-plugin</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <configuration>
        <bridges>
          <bridge>
            <sourceApplication>my-application</sourceApplication>
            <source>com.example.App#printOriginal</source>
            <dest>com.example.App#printRedirected</dest>
            <type>redirect</type>
          </bridge>
        </bridges>
      </configuration>
      <executions>
        <execution>
          <id>bcbridge</id>
          <phase>package</phase>
          <goals>
            <goal>rewrite</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Then package the consuming project:

```shell
mvn package
```

The build output will include:

```text
[INFO] --- bcbridge:1.0.0-SNAPSHOT:rewrite (bcbridge) @ your-project ---
[INFO] Redirecting com.example.App#printOriginal -> com.example.App#printRedirected
```

The `type` element is optional and defaults to `redirect`. Other planned types such as `onMethodEnter` and
`onMethodLeave` are rejected until they are implemented.

For `redirect`:

- Source and destination parameters must have identical types.
- The destination return type must be compatible with the source return type.
- A non-static destination class must have a no-argument constructor.
- Overloaded source methods are supported when every overload has a matching destination signature.
- Both `target/classes` and the packaged JAR are rewritten. Run the goal through `mvn package` so the JAR exists
  before rewriting begins.
