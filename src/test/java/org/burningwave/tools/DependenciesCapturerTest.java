package org.burningwave.tools;

import org.burningwave.core.io.FileSystemItem;
import org.burningwave.tools.DependenciesCapturer.Result;
import org.junit.jupiter.api.Test;

public class DependenciesCapturerTest extends BaseTest {
	
	@Test
	public void storeDependenciesTestOne() {
		testNotEmpty(() -> {
			Result dependencies = DependenciesCapturer.getInstance().captureAndStore(
				DependenciesCapturerTest.class, 
				System.getProperty("user.home") + "/Desktop/bw-tests",
				false, false, 0L
			);
			dependencies.waitForTaskEnding();
			return dependencies.getStore().getAllChildren();
		});
	}	

	
	public static void main(String[] args) {
		FileSystemItem.ofPath(System.getProperty("user.home")).getChildren();
	}
}
