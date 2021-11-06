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
import org.burningwave.tools.dependencies.Capturer.Result;
import org.burningwave.tools.dependencies.TwoPassCapturer;
import org.junit.jupiter.api.Test;

public class TwoPassCapturerTest extends BaseTest {

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
			Result result = TwoPassCapturer.getInstance().captureAndStore(
				"org.burningwave.tools.TwoPassCapturerTest",
				new String[]{"\"" + System.getProperty("user.home") + "\""},
				_paths,
				System.getProperty("user.home") + "/Desktop/bw-tests/TwoPassCapturer/testOne",
				true, 0L
			);
			result.waitForTaskEnding();
			return result.getJavaClasses();
		});
	}

	@Test
	public void storeDependenciesTestTwo() {
		testNotEmpty(() -> {
			ComponentSupplier componentSupplier = ComponentContainer.getInstance();
			PathHelper pathHelper = componentSupplier.getPathHelper();
			Collection<String> paths = pathHelper.getAllMainClassPaths();
			if (JVMInfo.getVersion() > 8) {
				paths.addAll(pathHelper.getPaths("dependencies-capturer.additional-resources-path"));
			}
			List<String> _paths = new ArrayList<>(paths);
			Collections.sort(_paths);
			String[] args = System.getProperty("os.name").toLowerCase().contains("windows") ?
				new String[]{"\"C:\\Program Files (x86)\""} :
				new String[]{"\"/\""};

			Result result = TwoPassCapturer.getInstance().captureAndStore(
				"org.burningwave.tools.TwoPassCapturerTest",
				args,
				_paths,
				System.getProperty("user.home") + "/Desktop/bw-tests/TwoPassCapturer/testTwo",
				true, 0L
			);
			result.waitForTaskEnding();
			return result.getJavaClasses();
		});
	}

	public static void main(String[] args) {
		String folderName = args[0];
		if (folderName.startsWith("\"")) {
			folderName = args[0].substring(1);
		}
		if (folderName.endsWith("\"")) {
			folderName = folderName.substring(0, folderName.length() -1);
		}
		for (FileSystemItem fileSystemItem : FileSystemItem.ofPath(folderName).getChildren()) {
			ManagedLoggersRepository.logDebug(() -> TwoPassCapturerTest.class.getName(), fileSystemItem.getAbsolutePath()
			);
		}
	}
}
