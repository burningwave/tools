[![logo](Burningwave-logo.jpg "Burningwave")](https://www.burningwave.org/)

**Burningwave Tools** is a set of components based on Burningwave Core library that have high-level functionality such as a dependencies extractor and a Java old versions to Java 13 application converter

# Dependencies extractors
By this functionality only the classes and resources strictly used by an application will extracted and stored in a specified path. At the end of the execution of the task, a script will be created in the destination path to run the application using the extracted classes. **The dependency extractors can also be used to adapt applications written with Java 8 to Java 9 and later**.

The classes that deal the dependencies extraction are:
1. **org.burningwave.tools.dependencies.Capturer**
2. **org.burningwave.tools.dependencies.TwoPassCapturer**

It can be used indiscriminately or one or the other class: the first performs a normal scan, the second a deep scan.
Here an example of the use of org.burningwave.tools.dependencies.TwoPassCapturer in **extraction mode**:

```java
package myextraction;

import static org.burningwave.core.assembler.StaticComponentsContainer.ManagedLoggersRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.burningwave.core.Component;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.io.PathHelper;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.burningwave.tools.dependencies.TwoPassCapturer;

public class DependenciesExtractor implements Component {
		
	public static void main(String[] args) throws Exception {
		long initialTime = System.currentTimeMillis();
		ComponentSupplier componentSupplier = ComponentContainer.getInstance();
		PathHelper pathHelper = componentSupplier.getPathHelper();
		Collection<String> paths = pathHelper.getPaths(PathHelper.MAIN_CLASS_PATHS, PathHelper.MAIN_CLASS_PATHS_EXTENSION);
		Result result = TwoPassCapturer.getInstance().captureAndStore(
			//Here you indicate the main class of your application			
			"my.class.that.contains.a.MainMethod",
			paths,
			//Here you indicate the destination path where extracted classes and resources will be stored	
			System.getProperty("user.home") + "/Desktop/dependencies",
			true,
			//Here you indicate the waiting time after the main of your application has been executed.
			//This is useful, for example, for spring boot applications to make it possible, once started,
			//to run rest methods to continue extracting the dependencies
			0L
		);
		result.waitForTaskEnding();
		ManagedLoggersRepository.logInfo(DependenciesExtractor.class, "Elapsed time: " + getFormattedDifferenceOfMillis(System.currentTimeMillis(), initialTime));
	}
	
	private static String getFormattedDifferenceOfMillis(long value1, long value2) {
		String valueFormatted = String.format("%04d", (value1 - value2));
		return valueFormatted.substring(0, valueFormatted.length() - 3) + "," + valueFormatted.substring(valueFormatted.length() -3);
	}

}
```

