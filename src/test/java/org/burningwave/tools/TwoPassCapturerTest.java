package org.burningwave.tools;

import org.burningwave.core.io.FileSystemItem;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.burningwave.tools.dependencies.TwoPassCapturer;
import org.junit.jupiter.api.Test;

public class TwoPassCapturerTest extends BaseTest {
	
	@Test
	public void storeDependenciesTestOne() {
		testNotEmpty(() -> {
			Result result = TwoPassCapturer.getInstance().captureAndStore(
				TwoPassCapturerTest.class, 
				System.getProperty("user.home") + "/Desktop/bw-tests",
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
