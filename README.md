# bcbridge-maven-plugin

A Maven plugin for rewriting Java bytecode on packaging.

Redirect functions inside libraries or on your on code.  
If what you need is:
- Print arguments for debugging inside libraries
- Send or enrich telemetry data
- Patch simple bugs without waiting for some release and without forking
- Mainly create workarounds for code that you have no access to the source

This is for you

Check [these examples](https://github.com/beothorn/bcbridge-examples/tree/main/mavenPlugin)

## Requirements

- Java 17 or newer
- Maven 3.9 or newer

## Add the plugin to a project

Add this configuration inside the consuming project's `<build>` element on the pom.xml.  
Replace the bridge entry:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>br.com.isageek</groupId>
      <artifactId>bcbridge-maven-plugin</artifactId>
      <version>1.0.0</version>
      <configuration>
        <bridges>
          <bridge>
            <!-- Here you put the original function. Can be on your code or some library -->
            <source>bcbridge.example.App#defaultMatcherOriginal</source>
            <!-- The function to where the call will be redicrected -->
            <dest>br.com.isageek.bcbridge.example.App#defaultMatcherRedirected</dest>
            <!-- Type is redirect, check the rest of the readme for other options -->
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

The changes will show on the logs.

# Available configurations

## `source`

| Value or syntax | Explanation |
| --- | --- |
| `namespace.Class#function` | Simple class-and-function matcher. Bare values use `nameContains`, so this selects declared functions whose name contains `function` in classes whose fully qualified name contains `namespace.Class`. |
| `<class-expression>#<function-expression>` | Selects declared functions using separate matcher expressions for the fully qualified class name and function name. The expression must match at least one declared function. Overloads are supported when every selected overload has a destination with the required signature. |
| `<class-expression>` | Selects every declared function in each matching class. |
| `named(value)` | Matches the entire name exactly (case-sensitive). |
| `namedIgnoreCase(value)` | Matches the entire name exactly, ignoring case. |
| `nameStartsWith(value)` | Matches names beginning with `value` (case-sensitive). |
| `nameStartsWithIgnoreCase(value)` | Matches names beginning with `value`, ignoring case. |
| `nameEndsWith(value)` | Matches names ending with `value` (case-sensitive). |
| `nameEndsWithIgnoreCase(value)` | Matches names ending with `value`, ignoring case. |
| `nameContains(value)` | Matches names containing `value` (case-sensitive). This is the matcher used by bare values. |
| `nameContainsIgnoreCase(value)` | Matches names containing `value`, ignoring case. |
| `nameMatches(regex)` | Matches a Java regular expression against the entire name. Add `.*` when arbitrary text is allowed before or after the relevant part. Matcher arguments are unquoted. |
| `a \|\| b` | Matches when either expression matches. |
| `a && b` | Matches when both expressions match. Write this as `a &amp;&amp; b` in XML. |
| `!a` | Matches when the expression does not match. |
| `(a)` | Groups expressions to control precedence. Group each `class#function` pair when combining several pairs. |

## `dest`

| Value or syntax | Explanation |
| --- | --- |
| `fully.qualified.Class#function` | Required reference to one exact destination function name. This is not a matcher: matcher functions, operators, and multiple function names are not allowed. When the Java function is overloaded, the plugin resolves the single overload whose parameters match the configured `captureArguments` and `thisAsParameter` values. A non-static destination's class must have a no-argument constructor. |

## `type`

| Value (case-sensitive) | Explanation |
| --- | --- |
| `redirect` (default) | Replaces the source implementation with a call to `dest`; the original implementation does not run. The destination return type must exactly match the source return type. |
| `OnMethodEnter` | Calls `dest` immediately before the original implementation. The original implementation still runs, and the destination must return `void`. |
| `OnMethodExit` | Calls `dest` after every normal return from the original implementation. It does not run after an exceptional exit, and the destination must return `void`. |

## `captureArguments`

| Value | Explanation |
| --- | --- |
| Omitted (default) | Passes no source arguments. Apart from a possible leading `Object` enabled by `thisAsParameter`, the destination must have no parameters. |
| `args` | Passes every source argument as a separate destination argument. The destination parameter types must exactly match the source parameter types, in the same order. |
| `array` | Passes all source arguments as one `Object[]`; primitive arguments are boxed. Apart from a possible leading `Object` enabled by `thisAsParameter`, the destination must have one `Object[]` parameter. |

## `thisAsParameter`

| Value | Explanation |
| --- | --- |
| `false` (default) | Does not pass the source object to the destination. |
| `true` | Passes the source object as the first destination parameter, before parameters selected by `captureArguments`. That parameter's declared type must be exactly `Object`. This cannot be used with a static source function. |

## Source matcher examples

For example:

```xml
<!-- One exact class and method -->
<source>named(com.example.App)#named(printOriginal)</source>

<!-- Methods beginning with "print" in classes ending in "Service" or "Controller" -->
<source>(nameEndsWith(Service)||nameEndsWith(Controller))#nameStartsWith(print)</source>

<!-- Combine conditions; XML escaping is required for && -->
<source>nameStartsWith(com.example.)&amp;&amp;!nameEndsWith(Test)#nameMatches(run.*)</source>

<!-- Regex in both the fully qualified class name and method name -->
<source>nameMatches(com[.]example[.].*Service)#nameMatches(find(ById|All))</source>
```

Both `target/classes` and the packaged JAR are rewritten. Run the goal through `mvn package` so the JAR exists
before rewriting begins.
