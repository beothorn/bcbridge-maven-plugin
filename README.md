# bcbridge-maven-plugin

A Maven plugin for rewriting Java bytecode with Byte Buddy during the `package` phase.

The `rewrite` goal can replace configured source method bodies (`redirect`) or call a destination immediately
before or after the original body (`OnMethodEnter` and `OnMethodExit`). The packaged application does not need
Byte Buddy, a Java agent, or runtime reflection.

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
            <source>named(com.example.App)#named(printOriginal)</source>
            <dest>com.example.App#printRedirected</dest>
            <type>redirect</type>
            <captureArguments>true</captureArguments>
            <thisAsParameter>false</thisAsParameter>
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

`type` defaults to `redirect`; the other supported values are the case-sensitive `OnMethodEnter` and
`OnMethodExit`. Advice destinations run before or after the original implementation and must return `void`.

`captureArguments` defaults to `true`. When false, source arguments are omitted from the destination call.
`thisAsParameter` defaults to `false`. When true, the source receiver is passed as the first destination parameter,
whose declared type must be exactly `Object`; this option cannot be used on a static source method.

## Source matcher expressions

`source` uses the JavaFlame matcher-expression parser. The part before `#` selects classes by fully qualified
name, and the optional part after `#` selects methods. If the method part is omitted, every declared method in a
matched class is redirected. The previous `com.example.App#printOriginal` form remains valid because bare values
use `nameContains`.

Available matcher functions are `named`, `namedIgnoreCase`, `nameStartsWith`, `nameStartsWithIgnoreCase`,
`nameEndsWith`, `nameEndsWithIgnoreCase`, `nameContains`, `nameContainsIgnoreCase`, and `nameMatches`. Expressions
also support `||`, `&&`, `!`, and parentheses. In XML, write `&&` as `&amp;&amp;`.

For example:

```xml
<!-- One exact class and method -->
<source>named(com.example.App)#named(printOriginal)</source>

<!-- Methods beginning with "print" in classes ending in "Service" or "Controller" -->
<source>(nameEndsWith(Service)||nameEndsWith(Controller))#nameStartsWith(print)</source>

<!-- Combine conditions; XML escaping is required for && -->
<source>nameStartsWith(com.example.)&amp;&amp;!nameEndsWith(Test)#nameMatches(run.*)</source>
```

Matcher arguments are unquoted strings. A source expression must match at least one declared method. `dest` is
not an expression: it remains one exact `fully.qualified.Class#method` reference, and a matching destination
overload is chosen for each selected source method.

For `redirect`:

- With `captureArguments=true`, destination parameters must match the source parameters exactly; with false, no
  source parameters are passed.
- With `thisAsParameter=true`, prepend an `Object` parameter to that signature.
- The destination return type must exactly match the source return type.
- A non-static destination class must have a no-argument constructor.
- Overloaded source methods are supported when every overload has a matching destination signature.
- Both `target/classes` and the packaged JAR are rewritten. Run the goal through `mvn package` so the JAR exists
  before rewriting begins.

For `OnMethodEnter` and `OnMethodExit`:

- The original method always executes. Enter runs immediately before it; exit runs after every normal return.
- The destination must return `void`.
- The same `captureArguments` and `thisAsParameter` parameter rules apply.

For example, an enter hook that receives nothing uses `captureArguments=false` and a zero-argument destination.
Adding `thisAsParameter=true` changes that destination signature to `(Object)`. An exit hook with both options true
uses `(Object, <all source parameter types>)`.
