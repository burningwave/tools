package org.burningwave.tools;

import org.junit.platform.runner.JUnitPlatform;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.runner.RunWith;

@RunWith(JUnitPlatform.class)
//@SelectPackages("org.burningwave.tools")
@ExcludeTags("Heavy")
@SelectClasses({
	CapturerTest.class,
	TwoPassCapturerTest.class,
	HostsResolverServiceTest.class
})
public class AllExceptHeavyTestsSuite {

}
