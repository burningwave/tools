package org.burningwave.tools;

import static org.burningwave.core.assembler.StaticComponentContainer.JVMInfo;
import static org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggersRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

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
			Collection<String> paths = pathHelper.getAllMainClassPaths();
			if (JVMInfo.getVersion() > 8) {
				paths.addAll(pathHelper.getPaths("dependencies-capturer.additional-resources-path"));
			}
			List<String> _paths = new ArrayList<>(paths);
			Collections.sort(_paths);
			Result dependencies = Capturer.getInstance().captureAndStore(
				"org.burningwave.tools.CapturerTest",
				_paths,
				System.getProperty("user.home") + "/Desktop/bw-tests/Capturer/testOne",
				true, 0L
			);
			dependencies.waitForTaskEnding();
			return dependencies.getStore().getAllChildren();
		});
	}	

	
	public static void main(String[] args) {
		for (FileSystemItem fileSystemItem : FileSystemItem.ofPath(System.getProperty("user.home")).getChildren()) {
			ManagedLoggersRepository.logDebug(fileSystemItem.getAbsolutePath()
			);
		}
	}
}
