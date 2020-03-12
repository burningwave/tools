/*
 * This file is part of Burningwave Tools.
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

import static org.burningwave.core.assembler.StaticComponentContainer.LowLevelObjectsHandler;
import static org.burningwave.core.assembler.StaticComponentContainer.Paths;
import static org.burningwave.core.assembler.StaticComponentContainer.Streams;
import static org.burningwave.core.assembler.StaticComponentContainer.Throwables;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.burningwave.core.Component;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ByteCodeHunter;
import org.burningwave.core.classes.Classes;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.FileSystemScanner;


public class Capturer implements Component {
	protected static final String ADDITIONAL_RESOURCES_PATH = "dependencies-capturer.additional-resources-path";
	protected static final String BURNINGWAVE_CLASSES_RELATIVE_DESTINATION_PATH = "[org.burningwave]";
	ByteCodeHunter byteCodeHunter;
	Classes.Loaders classesLoaders;
	FileSystemScanner fileSystemScanner;
	
	Capturer(
		FileSystemScanner fileSystemScanner,
		ByteCodeHunter byteCodeHunter,
		Classes.Loaders sourceCodeHandler
	) {
		this.fileSystemScanner = fileSystemScanner;
		this.byteCodeHunter = byteCodeHunter;
		this.classesLoaders = sourceCodeHandler;
	}
	
	public static Capturer create(ComponentSupplier componentSupplier) {
		return new Capturer(
			componentSupplier.getFileSystemScanner(),
			componentSupplier.getByteCodeHunter(),
			componentSupplier.getClassesLoaders()
		);
	}
	
	public static Capturer getInstance() {
		return LazyHolder.getCapturerInstance();
	}
	
	@SuppressWarnings("resource")
	public Result capture(
		String mainClassName,
		Collection<String> baseClassPaths,
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {	
		LowLevelObjectsHandler.disableIllegalAccessLogger();
		final Result result = new Result();
		Function<JavaClass, Boolean> javaClassAdder = includeMainClass ? 
			(javaClass) -> {
				result.put(javaClass);
				return true;
			}
			:(javaClass) -> {
				if (!javaClass.getName().equals(mainClassName)) {
					result.put(javaClass);
					return true;
				}
				return false;
			};
		result.findingTask = CompletableFuture.runAsync(() -> {
			Class<?> cls;
			try (Sniffer resourceSniffer = new Sniffer(null).init(
				false,
				fileSystemScanner,
				classesLoaders,
				baseClassPaths,
				javaClassAdder,
				fileSystemItem -> {
					result.putResource(fileSystemItem);
					return true;
				},
				resourceConsumer)
			) {
				try {
					cls = Class.forName(mainClassName, false, resourceSniffer);
					cls.getMethod("main", String[].class).invoke(null, (Object)new String[]{});
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
				} catch (Throwable exc) {
					throw Throwables.toRuntimeException(exc);				
				} finally {
					createExecutor(result.getStore().getAbsolutePath(), mainClassName, UUID.randomUUID().toString());
				}
			}
			LowLevelObjectsHandler.enableIllegalAccessLogger();
		});
		return result;
	}
	
	public Result captureAndStore(
		String mainClassName,
		Collection<String> baseClassPaths,
		String destinationPath,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		Result dependencies = capture(
			mainClassName,
			baseClassPaths, 
			getStoreFunction(destinationPath),
			includeMainClass,
			continueToCaptureAfterSimulatorClassEndExecutionFor
		);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}
	
	TriConsumer<String, String, ByteBuffer> getStoreFunction(String destinationPath) {
		//Exclude the runtime jdk library
		final String javaHome = Paths.clean(System.getProperty("java.home")) + "/";
		BiPredicate<String, FileSystemItem> storePredicate = (resourceAbsolutePath, fileSystemItem) -> 
			!resourceAbsolutePath.startsWith(javaHome) && 
			!fileSystemItem.exists();
		return (resourceAbsolutePath, resourceRelativePath, resourceContent) -> {
			String finalPath;
			if (resourceRelativePath.contains("tools")) {
				logDebug("Entered");
			}
			if (!resourceRelativePath.startsWith("org/burningwave")) {
				finalPath = getStoreEntryBasePath(destinationPath, resourceAbsolutePath, resourceRelativePath);
			} else {
				finalPath = destinationPath + "/" + BURNINGWAVE_CLASSES_RELATIVE_DESTINATION_PATH;
			}
			FileSystemItem fileSystemItem = FileSystemItem.ofPath(finalPath + "/" + resourceRelativePath);
			if (storePredicate.test(resourceAbsolutePath, fileSystemItem)) {
				Streams.store(fileSystemItem.getAbsolutePath(), resourceContent);
				logInfo("Resource {} has been stored to CLASSPATH {}", resourceRelativePath, fileSystemItem.getAbsolutePath());
			}
		};
	}
	
	
	String getStoreEntryBasePath(String storeBasePath, String itemAbsolutePath, String ItemRelativePath) {
		String finalPath = itemAbsolutePath;
		if (finalPath.chars().filter(ch -> ch == '/').count() > 1) {
			finalPath = finalPath.substring(0, finalPath.lastIndexOf(ItemRelativePath) - 1).substring(finalPath.indexOf("/") + 1);
			finalPath = "[" + finalPath.replace("/", "][") + "]";
		} else {
			finalPath = finalPath.replace("/", "");
		}
		return storeBasePath + "/" + getReducedPath(finalPath);
	}
	
	private String getReducedPath(String path) {
		String temp = path.substring(0, path.lastIndexOf("["));
		temp = temp.substring(0, temp.lastIndexOf("["));
		return path.substring(temp.lastIndexOf("["));
	}
	
	void createExecutor(String destinationPath, String mainClassName, String executorSuffix) {
		if (System.getProperty("os.name").toLowerCase().contains("windows")) {
			createWindowsExecutor(destinationPath, mainClassName, executorSuffix);
		} else {
			createUnixExecutor(destinationPath, mainClassName, executorSuffix);
		}
	}
	
	void createWindowsExecutor(String destinationPath, String mainClassName, String executorSuffix) {
		try {
			Set<String> classPathSet = FileSystemItem.ofPath(destinationPath).getChildren(fileSystemItem -> 
				fileSystemItem.isFolder()
			).stream().map(fileSystemItem -> 
				fileSystemItem.getAbsolutePath().replace(destinationPath + "/", "%~dp0")).collect(Collectors.toSet()
			);
			String externalExecutorForWindows = FileSystemItem.ofPath(
				System.getProperty("java.home")
			).getAbsolutePath() + "/bin/java -classpath \"" + String.join(";",	classPathSet) + "\" " + mainClassName;
			Files.write(java.nio.file.Paths.get(destinationPath + "/executor-" + executorSuffix + ".cmd"), externalExecutorForWindows.getBytes());
		} catch (Throwable exc) {
			logError("Exception occurred", exc);
		}
	}
	
	void createUnixExecutor(String destinationPath, String mainClassName, String executorSuffix) {
		try {
			Set<String> classPathSet = FileSystemItem.ofPath(destinationPath).getChildren(fileSystemItem -> 
				fileSystemItem.isFolder()
			).stream().map(fileSystemItem -> fileSystemItem.getAbsolutePath()).collect(Collectors.toSet());
			String externalExecutorForUnix = FileSystemItem.ofPath(System.getProperty("java.home")).getAbsolutePath() + "/bin/java -classpath " + String.join(":",	classPathSet) + " " + mainClassName;
			Files.write(java.nio.file.Paths.get(destinationPath + "/executor-" + executorSuffix + ".sh"), externalExecutorForUnix.getBytes());
		} catch (Throwable exc) {
			logError("Exception occurred", exc);
		}
	}
	
	public static class Result implements Component {
		CompletableFuture<Void> findingTask;
		Collection<FileSystemItem> resources;
		Collection<JavaClass> javaClasses;
		FileSystemItem store;
		
		Result() {
			this.javaClasses = new CopyOnWriteArrayList<>();
			this.resources = new CopyOnWriteArrayList<>();
		}
		
		public void putResource(FileSystemItem fileSystemItem) {
			resources.add(fileSystemItem);
		}
		
		JavaClass put(JavaClass javaClass) {
			javaClasses.add(javaClass);
			return javaClass;
		}
		
		public Collection<JavaClass> getJavaClasses() {
			return javaClasses;
		}
		
		public Collection<FileSystemItem> getResources() {
			return resources;
		}
		
		public JavaClass getJavaClass(Predicate<JavaClass> predicate) {
			return getJavaClasses().stream().filter(predicate).findFirst().orElseGet(() -> null);
		}
		
		public Collection<FileSystemItem> getResources(Predicate<FileSystemItem> predicate) {
			return getResources().stream().filter(predicate).collect(Collectors.toSet());
		}
		
		public CompletableFuture<Void> getFindingTask() {
			return this.findingTask;
		}
		
		public void waitForTaskEnding() {
			findingTask.join();
		}
		
		public FileSystemItem getStore() {
			return store;
		}
		
		@Override
		public void close() {
			findingTask.cancel(true);
			findingTask = null;
			resources.clear();
			resources = null;
			javaClasses.clear();
			javaClasses = null;
			store = null;
		}
	}
	
	static class LazyHolder {
		static final Capturer CAPTURER_INSTANCE = Capturer.create(ComponentContainer.getInstance());
		
		static Capturer getCapturerInstance() {
			return CAPTURER_INSTANCE;
		}
	}
}
