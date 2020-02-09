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

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.burningwave.Throwables;
import org.burningwave.core.Component;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.hunter.ByteCodeHunter;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileSystemHelper;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;
import org.burningwave.core.io.Streams;


public class Capturer implements Component {
	protected static final String ADDITIONAL_RESOURCES_PATH = "dependencies-capturer.additional-resources-path";
	ByteCodeHunter byteCodeHunter;
	PathHelper pathHelper;
	ClassHelper classHelper;
	FileSystemHelper fileSystemHelper;
	protected Collection<String> additionalClassPaths;
	
	Capturer(
		FileSystemHelper fileSystemHelper,
		PathHelper pathHelper,
		ByteCodeHunter byteCodeHunter,
		ClassHelper classHelper
	) {
		this.fileSystemHelper = fileSystemHelper;
		this.byteCodeHunter = byteCodeHunter;
		this.pathHelper = pathHelper;
		this.classHelper = classHelper;
		additionalClassPaths = pathHelper.getPaths(PathHelper.MAIN_CLASS_PATHS_EXTENSION, ADDITIONAL_RESOURCES_PATH);
	}
	
	public static Capturer create(ComponentContainer componentSupplier) {
		return new Capturer(
			componentSupplier.getFileSystemHelper(),
			componentSupplier.getPathHelper(),
			componentSupplier.getByteCodeHunter(),
			componentSupplier.getClassHelper()
		);
	}
	
	public static Capturer getInstance() {
		return LazyHolder.getCapturerInstance();
	}
	
	public Result capture(
		Class<?> mainClass,
		Collection<String> _baseClassPaths,
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {	
		Collection<String> baseClassPaths = new LinkedHashSet<>(_baseClassPaths);
		baseClassPaths.addAll(additionalClassPaths);
		final Result result = new Result();
		Function<JavaClass, Boolean> javaClassAdder = includeMainClass ? 
			(javaClass) -> {
				result.put(javaClass);
				return true;
			}
			:(javaClass) -> {
				if (!javaClass.getName().equals(mainClass.getName())) {
					result.put(javaClass);
					return true;
				}
				return false;
			};
		result.findingTask = CompletableFuture.runAsync(() -> {
			Class<?> cls;
			try (Sniffer resourceSniffer = new Sniffer(
				false,
				fileSystemHelper,
				classHelper,
				baseClassPaths,
				javaClassAdder,
				fileSystemItem -> {
					result.putResource(fileSystemItem);
					return true;
				},
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
		});
		return result;
	}
	
	public Result captureAndStore(
		Class<?> mainClass,
		String destinationPath,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		return captureAndStore(mainClass, pathHelper.getMainClassPaths(), destinationPath, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor);
	}
	
	public Result captureAndStore(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		String destinationPath,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		Result dependencies = capture(
			mainClass,
			baseClassPaths, 
			getStoreFunction(destinationPath),
			includeMainClass,
			continueToCaptureAfterSimulatorClassEndExecutionFor
		);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}
	
	TriConsumer<String, String, ByteBuffer> getStoreFunction(String destinationPath) {
		return (resourceAbsolutePath, resourceRelativePath, resourceContent) -> {
			String finalPath = getStoreEntryBasePath(destinationPath, resourceAbsolutePath, resourceRelativePath);
			FileSystemItem fileSystemItem = FileSystemItem.ofPath(finalPath + "/" + resourceRelativePath);
			if (!fileSystemItem.exists()) {
				Streams.store(fileSystemItem.getAbsolutePath(), resourceContent);
				logDebug("Resource {} has been stored to CLASSPATH {}", resourceRelativePath, fileSystemItem.getAbsolutePath());
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
		return storeBasePath + "/" + finalPath;
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
