/*
 * This file is part of Burningwave Core.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/tools
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.tools.dependencies;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.burningwave.Throwables;
import org.burningwave.core.ManagedLogger;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ClassCriteria;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.hunter.ByteCodeHunter;
import org.burningwave.core.classes.hunter.ByteCodeHunter.SearchResult;
import org.burningwave.core.classes.hunter.ClassPathHunter;
import org.burningwave.core.classes.hunter.SearchConfig;
import org.burningwave.core.common.Strings;
import org.burningwave.core.function.QuadConsumer;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;


public class TwoPassCapturer extends Capturer {
	ClassPathHunter classPathHunter;
	
	private TwoPassCapturer(
		PathHelper pathHelper,
		ByteCodeHunter byteCodeHunter,
		ClassPathHunter classPathHunter,
		ClassHelper classHelper
	) {
		super(pathHelper, byteCodeHunter, classHelper);
		this.classPathHunter = classPathHunter;
	}
	
	public static TwoPassCapturer create(ComponentSupplier componentSupplier) {
		return new TwoPassCapturer(
			componentSupplier.getPathHelper(),
			componentSupplier.getByteCodeHunter(),
			componentSupplier.getClassPathHunter(),
			componentSupplier.getClassHelper()
		);
	}
	
	public static TwoPassCapturer getInstance() {
		return LazyHolder.getCapturerInstance();
	}
	
	@Override
	public Result capture(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		QuadConsumer<String, String, String, ByteBuffer>  javaClassConsumer,
		QuadConsumer<String, String, String, ByteBuffer>  resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		return capture(mainClass, baseClassPaths, javaClassConsumer, resourceConsumer, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, true);
	}
	
	public Result capture(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		QuadConsumer<String, String, String, ByteBuffer>  javaClassConsumer,
		QuadConsumer<String, String, String, ByteBuffer>  resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		final Result result;
		try (SearchResult searchResult = byteCodeHunter.findBy(
			SearchConfig.forPaths(
				baseClassPaths
			)
		)) {
			result = new Result(
				searchResult.getClassesFlatMap(), 
				javaClassConsumer,
				resourceConsumer
			);
		}
		Consumer<String> classNamePutter = includeMainClass ? 
			(className) -> 
				result.put(className) 
			:(className) -> {
				if (!className.equals(mainClass.getName())) {
					result.put(className);
				}
			};
		result.findingTask = CompletableFuture.runAsync(() -> {
			ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
			Class<?> cls;
			try (Sniffer resourceSniffer = new Sniffer(contextClassLoader, classHelper, classNamePutter, result::putResource)) {
				Thread.currentThread().setContextClassLoader(resourceSniffer);
				for (Entry<String, JavaClass> entry : result.classPathClasses.entrySet()) {
					JavaClass javaClass = entry.getValue();
					resourceSniffer.addCompiledClass(javaClass.getName(), javaClass.getByteCode());
				}
				try {
					cls = classHelper.loadOrUploadClass(mainClass, resourceSniffer);
					cls.getMethod("main", String[].class).invoke(null, (Object)new String[]{});
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
					if (recursive) {
						launchExternalCapturer(
							mainClass, result.getStore().getAbsolutePath(), baseClassPaths, 
							resourceConsumer != null, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor
						);
					}
				} catch (Throwable exc) {
					throw Throwables.toRuntimeException(exc);				
				} finally {
					Thread.currentThread().setContextClassLoader(contextClassLoader);
				}
			}
		});
		return result;
	}
	
	private void launchExternalCapturer(
		Class<?> mainClass, String destinationPath, Collection<String> baseClassPaths,
		boolean storeAllResources, boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) throws IOException, InterruptedException {
		String javaExecutablePath = System.getProperty("java.home") + "/bin/java";
		List<String> command = new LinkedList<String>();
        command.add(Strings.Paths.clean(javaExecutablePath));
        command.add("-cp");
        StringBuffer generatedClassPath = new StringBuffer("\"");
        Collection<String> classPathsToBeScanned = new LinkedHashSet<>(baseClassPaths);
        classPathsToBeScanned.remove(destinationPath);
        List<String> classPaths = FileSystemItem.ofPath(destinationPath).getChildren().stream().map(
        	child -> child.getAbsolutePath()
        ).collect(Collectors.toList());
        generatedClassPath.append(String.join(System.getProperty("path.separator"), classPaths));
        ClassPathHunter.SearchResult searchResult = classPathHunter.findBy(
			SearchConfig.forPaths(
				baseClassPaths
			).by(
				ClassCriteria.create().className(clsName -> 
					clsName.equals(this.getClass().getName()) || clsName.equals(ComponentSupplier.class.getName())
				)
			)
    	);
        Iterator<FileSystemItem> classPathIterator = searchResult.getClassPaths().iterator();
        while (classPathIterator.hasNext()) {
        	FileSystemItem classPath = classPathIterator.next();
        	if (!generatedClassPath.toString().contains(classPath.getAbsolutePath())) {	
	        	generatedClassPath.append(
	        		System.getProperty("path.separator")
	            );
	        	generatedClassPath.append(
	        		classPath.getAbsolutePath()
	        	);
	        	classPathsToBeScanned.remove(classPath.getAbsolutePath());
        	}
        }
        generatedClassPath.append("\"");
        command.add(generatedClassPath.toString());
        command.add(this.getClass().getName());
        command.add(mainClass.getName());
        String classPathsToBeScannedParam = "\"" + String.join(System.getProperty("path.separator"), classPathsToBeScanned) + "\"";
        command.add(classPathsToBeScannedParam);        
        command.add("\"" + destinationPath + "\"");
        command.add(Boolean.valueOf(storeAllResources).toString());
        command.add(Boolean.valueOf(includeMainClass).toString());
        command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
        ProcessBuilder builder = new ProcessBuilder(command);

        Process process = builder.inheritIO().start();
        
        process.waitFor();

	}
	
	public static void main(String[] args) throws ClassNotFoundException {
		Class.forName(ManagedLogger.class.getName());
		String mainClassName = args[0];
		Collection<String> paths = Arrays.asList(args[1].split(System.getProperty("path.separator")));
		String destinationPath = args[2];
		boolean storeAllResources = Boolean.valueOf(args[3]);
		boolean includeMainClass = Boolean.valueOf(args[4]);
		long continueToCaptureAfterSimulatorClassEndExecutionFor = Long.valueOf(args[5]);
		Class<?> mainClass = Class.forName(mainClassName);
		
		TwoPassCapturer.getInstance().captureAndStore(
			mainClass, paths, destinationPath, storeAllResources, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, false
		).waitForTaskEnding();
	}
	
	private Result captureAndStore(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		String destinationPath,
		boolean storeResources,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		Result dependencies = capture(
			mainClass,
			baseClassPaths, getStoreFunction(),
			storeResources ?
				getStoreFunction()
				: null,
			includeMainClass,
			continueToCaptureAfterSimulatorClassEndExecutionFor,
			true
		);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}
	
	private static class Result extends Capturer.Result {
		Result(Map<String, JavaClass> classPathClasses,
				QuadConsumer<String, String, String, ByteBuffer> javaClassConsumer,
				QuadConsumer<String, String, String, ByteBuffer> resourceConsumer) {
			super(classPathClasses, javaClassConsumer, resourceConsumer);
		}

	
	}
	
	private static class LazyHolder {
		private static final TwoPassCapturer CAPTURER_INSTANCE = TwoPassCapturer.create(ComponentContainer.getInstance());
		
		private static TwoPassCapturer getCapturerInstance() {
			return CAPTURER_INSTANCE;
		}
	}
}
