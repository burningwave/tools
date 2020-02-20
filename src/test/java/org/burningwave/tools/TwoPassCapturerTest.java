package org.burningwave.tools;

import java.util.Collection;

import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.burningwave.tools.dependencies.TwoPassCapturer;
import org.junit.jupiter.api.Test;

public class TwoPassCapturerTest extends BaseTest {
	
	@Test
	public void storeDependenciesTestOne() {
		testNotEmpty(() -> {
			ComponentSupplier componentSupplier = ComponentContainer.getInstance();
			PathHelper pathHelper = componentSupplier.getPathHelper();
			Collection<String> paths = pathHelper.getPaths("dependencies-capturer.additional-resources-path");
			paths.addAll(pathHelper.getMainClassPaths());
			Result result = TwoPassCapturer.getInstance().captureAndStore(
				"org.burningwave.tools.TwoPassCapturerTest",
				paths,
				System.getProperty("user.home") + "/Desktop/bw-tests/dependencies",
				false, 0L
			);
			result.waitForTaskEnding();
			return result.getJavaClasses();
		});
	}	

	
	public static void main(String[] args) {
		FileSystemItem.ofPath(System.getProperty("user.home")).getChildren();
	}
}
