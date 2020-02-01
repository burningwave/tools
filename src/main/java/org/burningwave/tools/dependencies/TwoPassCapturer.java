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
import org.burningwave.core.classes.hunter.ClassPathHunter;
import org.burningwave.core.classes.hunter.SearchConfig;
import org.burningwave.core.common.Strings;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileSystemHelper;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;


public class TwoPassCapturer extends Capturer {
	ClassPathHunter classPathHunter;
	
	private TwoPassCapturer(
		FileSystemHelper fileSystemHelper,
		PathHelper pathHelper,
		ByteCodeHunter byteCodeHunter,
		ClassPathHunter classPathHunter,
		ClassHelper classHelper
	) {
		super(fileSystemHelper, pathHelper, byteCodeHunter, classHelper);
		this.classPathHunter = classPathHunter;
	}
	
	public static TwoPassCapturer create(ComponentSupplier componentSupplier) {
		return new TwoPassCapturer(
			componentSupplier.getFileSystemHelper(),
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
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		return capture(mainClass, baseClassPaths, resourceConsumer, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, true);
	}
	
	public Result capture(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		final Result result = new Result();
		Consumer<JavaClass> javaClassAdder = includeMainClass ? 
			(javaClass) -> 
				result.put(javaClass) 
			:(javaClass) -> {
				if (!javaClass.getName().equals(mainClass.getName())) {
					result.put(javaClass);
				}
			};
		result.findingTask = CompletableFuture.runAsync(() -> {
			Class<?> cls;
			try (Sniffer resourceSniffer = new Sniffer(
				!recursive,
				baseClassPaths,
				fileSystemHelper,
				classHelper,
				javaClassAdder,
				result::putResource,
				resourceConsumer)
			) {
				try {
					cls = classHelper.loadOrUploadClass(mainClass, resourceSniffer);
					cls.getMethod("main", String[].class).invoke(null, (Object)new String[]{});
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
				} catch (Throwable exc) {
					throw Throwables.toRuntimeException(exc);				
				}
			}
			if (recursive) {
				try {
					launchExternalCapturer(
						mainClass, result.getStore().getAbsolutePath(), baseClassPaths, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor
					);
				} catch (IOException | InterruptedException exc) {
					throw Throwables.toRuntimeException(exc);
				}
			}
		});
		return result;
	}
	
	private Result captureAndStore(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		String destinationPath,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		Result dependencies = capture(
			mainClass,
			baseClassPaths,
			getStoreFunction(destinationPath),
			includeMainClass,
			continueToCaptureAfterSimulatorClassEndExecutionFor,
			recursive
		);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}
	
	private void launchExternalCapturer(
		Class<?> mainClass, String destinationPath, Collection<String> baseClassPaths, boolean includeMainClass,
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
        //Adding Burningwave to classpath
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
        String classPathsToBeScannedParam = "\"" + String.join(System.getProperty("path.separator"), classPathsToBeScanned) + "\"";
        command.add(classPathsToBeScannedParam);
        command.add(mainClass.getName());
        command.add("\"" + destinationPath + "\"");
        command.add(Boolean.valueOf(includeMainClass).toString());
        command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
        ProcessBuilder builder = new ProcessBuilder(command);

        Process process = builder.inheritIO().start();
        
        process.waitFor();

	}
	
	public static void main(String[] args) throws ClassNotFoundException {
		logReceivedParameters(args, 0);
		Class.forName(ManagedLogger.class.getName());		
		Collection<String> paths = Arrays.asList(args[0].split(System.getProperty("path.separator")));
		String mainClassName = args[1];		
		String destinationPath = args[2];
		boolean includeMainClass = Boolean.valueOf(args[3]);
		long continueToCaptureAfterSimulatorClassEndExecutionFor = Long.valueOf(args[4]);
		Class<?> mainClass = Class.forName(mainClassName);
		TwoPassCapturer.getInstance().captureAndStore(
			mainClass, paths, destinationPath, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, false
		).waitForTaskEnding();
	}
	
	private static void logReceivedParameters(String[] args, long wait) {
		try {
			ManagedLogger.Repository.logDebug(TwoPassCapturer.class, "classpath: {}", System.getProperty("java.class.path"));
			ManagedLogger.Repository.logDebug(TwoPassCapturer.class, "path to be scanned: {}",
				String.join(";",
					Arrays.asList(
						args[0].split(System.getProperty("path.separator"))
					)
				)
			);
			ManagedLogger.Repository.logDebug(TwoPassCapturer.class, "mainClassName: {}", args[1]);
			ManagedLogger.Repository.logDebug(TwoPassCapturer.class, "destinationPath: {}", args[2]);
			ManagedLogger.Repository.logDebug(TwoPassCapturer.class, "includeMainClass: {}", args[3]);
			ManagedLogger.Repository.logDebug(TwoPassCapturer.class, "continueToCaptureAfterSimulatorClassEndExecutionFor: {}", args[4]);
			if (wait > 0) {
				Thread.sleep(wait);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	private static class Result extends Capturer.Result {
		Result() {
			super();
		}

	
	}
	
	private static class LazyHolder {
		private static final TwoPassCapturer CAPTURER_INSTANCE = TwoPassCapturer.create(ComponentContainer.getInstance());
		
		private static TwoPassCapturer getCapturerInstance() {
			return CAPTURER_INSTANCE;
		}
	}
}
