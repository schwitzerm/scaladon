// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "ca.schwitzer"

// To sync with Maven central, you need to supply the following information:
pomExtra in Global := {
  <url>https://github.com/schwitzerm/scaladon</url>

    <licenses>
      <license>
        <name>WTFPL</name>
        <url>http://www.wtfpl.net/txt/copying/</url>
      </license>
    </licenses>

    <scm>
      <connection>scm:git:github.com/schwitzerm/scaladon</connection>
      <developerConnection>scm:git:git@github.com:schwitzerm/scaladon</developerConnection>
      <url>github.com/schwitzerm/scaladon</url>
    </scm>

    <developers>
      <developer>
        <id>Mellow_</id>
        <name>Mitchell Schwitzer</name>
        <url>https://blog.schwitzer.ca</url>
      </developer>
    </developers>
}
