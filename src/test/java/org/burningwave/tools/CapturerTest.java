package org.burningwave.tools;

import java.util.Collection;

import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;
import org.burningwave.tools.dependencies.Capturer;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.junit.jupiter.api.Test;

public class CapturerTest extends BaseTest {
	
	@Test
	public void storeDependenciesTestOne() {
		testNotEmpty(() -> {
			ComponentSupplier componentSupplier = ComponentContainer.getInstance();
			PathHelper pathHelper = componentSupplier.getPathHelper();
			Collection<String> paths = pathHelper.getPaths(PathHelper.MAIN_CLASS_PATHS, PathHelper.MAIN_CLASS_PATHS_EXTENSION);
			if (componentSupplier.getJVMInfo().getVersion() > 8) {
				paths.addAll(pathHelper.getPaths("dependencies-capturer.additional-resources-path"));
			}
			Result dependencies = Capturer.getInstance().captureAndStore(
				"org.burningwave.tools.CapturerTest",
				paths,
				System.getProperty("user.home") + "/Desktop/bw-tests/dependencies",
				false, 0L
			);
			dependencies.waitForTaskEnding();
			return dependencies.getStore().getAllChildren();
		});
	}	

	
	public static void main(String[] args) {
		FileSystemItem.ofPath(System.getProperty("user.home")).getChildren();
	}
}
