Burningwave Tools [![Tweet](https://img.shields.io/twitter/url/http/shields.io.svg?style=social)](https://twitter.com/intent/tweet?text=Dependencies%20shrinking%20and%20making%20applications%20created%20with%20old%20%23Java%20versions%20work%20on%20%23Java8%20%23Java9%20%23Java10%20%23Java11%20%23Java12%20%23Java13%20%23Java14%20with%20%40Burningwave1%20Tools&url=https://github.com/burningwave/tools/wiki)
==========

<a href="https://github.com/burningwave/tools">
<img src="https://raw.githubusercontent.com/burningwave/tools/master/Burningwave-logo.png" alt="Burningwave-logo.png" height="180px" align="right"/>
</a>

[![Maven Central with version prefix filter](https://img.shields.io/maven-central/v/org.burningwave/tools/0)](https://maven-badges.herokuapp.com/maven-central/org.burningwave/tools/)
[![GitHub](https://img.shields.io/github/license/burningwave/tools)](https://github.com/burningwave/tools/blob/master/LICENSE)

[![Supported JVM](https://img.shields.io/badge/Supported%20JVM-8%2C%209%2C%2010%2C%2011%2C%2012%2C%2013%2C%2014%2C%2015ea-blueviolet)](https://github.com/burningwave/tools/actions/runs/105661160)
[![Coverage Status](https://coveralls.io/repos/github/burningwave/tools/badge.svg?branch=master)](https://coveralls.io/github/burningwave/tools?branch=master)
[![GitHub issues](https://img.shields.io/github/issues/burningwave/tools)](https://github.com/burningwave/tools/issues)

**Burningwave Tools** is a set of components based on Burningwave Core library that have high-level functionality

# Dependencies shrinking
By this functionality only the classes and resources strictly used by an application will be extracted and stored in a specified path. At the end of the execution of the task, a script will be created in the destination path to run the application using the extracted classes. **The dependency shrinkers can also be used to adapt applications written with Java old versions to Java 9 or later**.

The classes that deal the dependencies extraction are:
* **org.burningwave.tools.dependencies.Capturer**
* **org.burningwave.tools.dependencies.TwoPassCapturer**

It can be used indiscriminately or one or the other class: the first performs a normal scan, the second a deep scan. **When the operations are finished a batch will be generated in the destination path to run your application with the extracted dependencies**.

To use dependencies shrinkers in your project add this to your pom:
```xml
<dependency>
    <groupId>org.burningwave</groupId>
    <artifactId>tools</artifactId>
    <version>0.9.32</version>
</dependency>	
```
<br/>

## Extractor mode
To use this mode simply pass to the method **captureAndStore**, as first parameter, the name of the class of your application that contains the main method.
```java
package org.burningwave.tools.examples.twopasscapturer;

import static
    org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggersRepository;

import java.util.Collection;

import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.io.PathHelper;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.burningwave.tools.dependencies.TwoPassCapturer;

public class DependenciesExtractor {    
    
    public static void main(String[] args) throws Exception {
        long initialTime = System.currentTimeMillis();
        ComponentSupplier componentSupplier = ComponentContainer.getInstance();
        PathHelper pathHelper = componentSupplier.getPathHelper();
        Collection<String> paths = pathHelper.getPaths(
            PathHelper.MAIN_CLASS_PATHS,
            PathHelper.MAIN_CLASS_PATHS_EXTENSION
        );
        Result result = TwoPassCapturer.getInstance().captureAndStore(
            //Here you indicate the main class of your application            
            "my.class.that.contains.a.MainMethod",
            paths,
            //Here you indicate the destination path where extracted
            //classes and resources will be stored    
            System.getProperty("user.home") + "/Desktop/dependencies",
            true,
            //Here you indicate the waiting time after the main of your
            //application has been executed. This is useful, for example, 
            //for spring boot applications to make it possible, once started,
            //to run rest methods to continue extracting the dependencies
            0L
        );
        result.waitForTaskEnding();
        ManagedLoggersRepository.logInfo(
            DependenciesExtractor.class, 
            "Elapsed time: " + getFormattedDifferenceOfMillis(
                System.currentTimeMillis(), initialTime
            )
        );
    }
    
    private static String getFormattedDifferenceOfMillis(long value1, long value2) {
        String valueFormatted = String.format("%04d", (value1 - value2));
        return valueFormatted.substring(0, valueFormatted.length() - 3) + "," +
        valueFormatted.substring(valueFormatted.length() -3);
    }

}
```
<br/>

## Adapter mode
In this mode you can adapt a Java old version application to Java 9 or later. To use this mode you must **run the main of the application adapter with a jdk 9 or later**, load, by using PathHelper, the jdk libraries by which the target application was developed and pass to the method **captureAndStore**, as first parameter, the name of the class of your application that contains the main method. In the example below we adapt a Java 8 application to Java 9 or later.
```java
package org.burningwave.tools.examples.twopasscapturer;

import static
    org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggersRepository;

import java.util.Collection;

import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.io.PathHelper;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.burningwave.tools.dependencies.TwoPassCapturer;

public class ApplicationAdapter {    
    
    public static void main(String[] args) throws Exception {
        long initialTime = System.currentTimeMillis();
        ComponentSupplier componentSupplier = ComponentContainer.getInstance();
        PathHelper pathHelper = componentSupplier.getPathHelper();
        Collection<String> paths = pathHelper.getPaths(
            PathHelper.MAIN_CLASS_PATHS,
            PathHelper.MAIN_CLASS_PATHS_EXTENSION
        );
        String jdk8Home = "C:/Program Files/Java/jdk1.8.0_172";
        //Add jdk 8 library
        paths.addAll(
            pathHelper.loadPaths(
                "dependencies-capturer.additional-resources-path", 
                "//" + jdk8Home + "/jre/lib//children:.*\\.jar;" +
                "//" + jdk8Home + "/jre/lib/ext//children:.*\\.jar;"
            )
        );
        Result result = TwoPassCapturer.getInstance().captureAndStore(
            //Here you indicate the main class of your application            
            "my.class.that.contains.a.MainMethod",
            paths,
            //Here you indicate the destination path where extracted
            //classes and resources will be stored    
            System.getProperty("user.home") + "/Desktop/dependencies",
            true,
            //Here you indicate the waiting time after the main of your
            //application has been executed. This is useful, for example, 
            //for spring boot applications to make it possible, once started,
            //to run rest methods to continue extracting the dependencies
            0L
        );
        result.waitForTaskEnding();
        ManagedLoggersRepository.logInfo(
            DependenciesExtractor.class, 
            "Elapsed time: " + getFormattedDifferenceOfMillis(
                System.currentTimeMillis(),
                initialTime
            )
        );
    }
    
    private static String getFormattedDifferenceOfMillis(long value1, long value2) {
        String valueFormatted = String.format("%04d", (value1 - value2));
        return valueFormatted.substring(0, valueFormatted.length() - 3) + "," +
        valueFormatted.substring(valueFormatted.length() -3);
    }

}
```

## [**Ask for assistance to Burningwave community**](https://www.burningwave.org/forum/forum/how-to-do-2/)
[![HitCount](http://hits.dwyl.com/burningwave/all.svg)](http://hits.dwyl.com/burningwave/all)
<a href="https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=EY4TMTW8SWDAC&item_name=Support+maintenance+and+improvement+of+Burningwave&currency_code=EUR&source=url" rel="nofollow"><img src="https://camo.githubusercontent.com/e14c85b542e06215f7e56c0763333ef1e9b9f9b7/68747470733a2f2f7777772e70617970616c6f626a656374732e636f6d2f656e5f55532f692f62746e2f62746e5f646f6e6174655f534d2e676966" alt="Donate" data-canonical-src="https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif" style="max-width:100%;"></a>
