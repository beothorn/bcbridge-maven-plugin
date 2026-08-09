# bcbridge-maven-plugin

A Maven plugin for rewriting Java bytecode with Byte Buddy during the `package` phase.

The first milestone provides a `hello` goal that prints `Hello World` during a build.

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
      <executions>
        <execution>
          <id>bcbridge</id>
          <phase>package</phase>
          <goals>
            <goal>hello</goal>
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
[INFO] --- bcbridge:1.0.0-SNAPSHOT:hello (bcbridge) @ your-project ---
[INFO] Hello World
```

You can also invoke the goal directly after installing the plugin locally:

```shell
mvn br.com.isageek:bcbridge-maven-plugin:1.0.0-SNAPSHOT:hello
```
