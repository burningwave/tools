package org.burningwave.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.burningwave.ManagedLogger;
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
			Collection<String> paths = pathHelper.getPaths(PathHelper.MAIN_CLASS_PATHS, PathHelper.MAIN_CLASS_PATHS_EXTENSION);
			if (componentSupplier.getJVMInfo().getVersion() > 8) {
				paths.addAll(pathHelper.getPaths("dependencies-capturer.additional-resources-path"));
			}
			List<String> _paths = new ArrayList<>(paths);
			Collections.sort(_paths);
			Result result = TwoPassCapturer.getInstance().captureAndStore(
				"org.burningwave.tools.TwoPassCapturerTest",
				_paths,
				System.getProperty("user.home") + "/Desktop/bw-tests/dependencies",
				true, 0L
			);
			result.waitForTaskEnding();
			return result.getJavaClasses();
		});
	}	

	
	public static void main(String[] args) {
		for (FileSystemItem fileSystemItem : FileSystemItem.ofPath(System.getProperty("user.home")).getChildren()) {
			ManagedLogger.Repository.getInstance().logDebug(
				TwoPassCapturerTest.class, fileSystemItem.getAbsolutePath()
			);
		}
	}
}
