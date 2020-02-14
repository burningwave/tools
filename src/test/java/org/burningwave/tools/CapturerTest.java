package org.burningwave.tools;

import org.burningwave.core.io.FileSystemItem;
import org.burningwave.tools.dependencies.Capturer;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.junit.jupiter.api.Test;

public class CapturerTest extends BaseTest {
	
	@Test
	public void storeDependenciesTestOne() {
		testNotEmpty(() -> {
			Result dependencies = Capturer.getInstance().captureAndStore(
				CapturerTest.class, 
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
