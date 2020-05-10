Burningwave Tools [![Tweet](https://img.shields.io/twitter/url/http/shields.io.svg?style=social)](https://twitter.com/intent/tweet?text=Burningwave%20Core%3A%20a%20%23Java%20frameworks%20building%20library%20with%20an%20original%20classpath%20scan%20engine%20(works%20on%20%23Java8%20%23Java9%20%23Java10%20%23Java11%20%23Java12%20%23Java13%20%23Java14)&url=https://github.com/burningwave/tools/wiki)
==========

<a href="https://github.com/burningwave/tools/wiki">
<img src="https://raw.githubusercontent.com/burningwave/core/master/Burningwave-logo.jpg" alt="Burningwave-logo.jpg" height="180px" align="right"/>
</a>

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.burningwave/tools/badge.svg#)](https://search.maven.org/artifact/org.burningwave/tools)

[![Tested on Java 8](https://img.shields.io/badge/Tested%20on-Java%208-yellowgreen)](https://www.java.com/it/download/)
[![Tested on Java 9](https://img.shields.io/badge/Tested%20on-Java%209-yellow)](https://www.oracle.com/java/technologies/javase/javase9-archive-downloads.html)
[![Tested on Java 10](https://img.shields.io/badge/Tested%20on-Java%2010-orange)](https://www.oracle.com/java/technologies/java-archive-javase10-downloads.html)
[![Tested on Java 11](https://img.shields.io/badge/Tested%20on-Java%2011-red)](https://www.oracle.com/java/technologies/javase-jdk11-downloads.html)

[![Tested on Java 12](https://img.shields.io/badge/Tested%20on-Java%2012-ff69b4)](https://www.oracle.com/java/technologies/javase/jdk12-archive-downloads.html)
[![Tested on Java 13](https://img.shields.io/badge/Tested%20on-Java%2013-blueviolet)](https://www.oracle.com/java/technologies/javase/jdk13-archive-downloads.html)
[![Tested on Java 14](https://img.shields.io/badge/Tested%20on-Java%2014-blue)](https://www.oracle.com/java/technologies/javase-downloads.html)

**Burningwave Tools** is a set of components based on Burningwave Core library that have high-level functionality
## Dependencies shrinking
By this functionality only the classes and resources strictly used by an application will be extracted and stored in a specified path. At the end of the execution of the task, a script will be created in the destination path to run the application using the extracted classes. **The dependency shrinkers can also be used to adapt applications written with Java old versions to Java 9 or later**.

## Adapter mode
In this mode you can adapt a Java old version application to Java 9 or later. To use this mode you must **run the main of the application adapter with a jdk 9 or later**, load, by using PathHelper, the jdk libraries by which the target application was developed and pass to the method **captureAndStore**, as first parameter, the name of the class of your application that contains the main method. In the example below we adapt a Java 8 application to Java 9 or later.

## [Get started](https://github.com/burningwave/tools/wiki)
## [**Ask for assistance to Burningwave community**](https://www.burningwave.org/forum/forum/how-to-do-2/)
<a href="https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=EY4TMTW8SWDAC&item_name=Support+maintenance+and+improvement+of+Burningwave&currency_code=EUR&source=url" rel="nofollow"><img src="https://camo.githubusercontent.com/e14c85b542e06215f7e56c0763333ef1e9b9f9b7/68747470733a2f2f7777772e70617970616c6f626a656374732e636f6d2f656e5f55532f692f62746e2f62746e5f646f6e6174655f534d2e676966" alt="Donate" data-canonical-src="https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif" style="max-width:100%;"></a>
