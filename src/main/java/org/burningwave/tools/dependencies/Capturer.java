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
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.burningwave.Throwables;
import org.burningwave.core.Component;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.hunter.ByteCodeHunter;
import org.burningwave.core.classes.hunter.ByteCodeHunter.SearchResult;
import org.burningwave.core.classes.hunter.SearchConfig;
import org.burningwave.core.common.Strings;
import org.burningwave.core.function.QuadConsumer;
import org.burningwave.core.io.FileInputStream;
import org.burningwave.core.io.FileSystemHelper.Scan;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;
import org.burningwave.core.io.Streams;
import org.burningwave.core.io.ZipInputStream;


public class Capturer implements Component {
	ByteCodeHunter byteCodeHunter;
	PathHelper pathHelper;
	ClassHelper classHelper;
	
	Capturer(
		PathHelper pathHelper,
		ByteCodeHunter byteCodeHunter,
		ClassHelper classHelper
	) {
		this.byteCodeHunter = byteCodeHunter;
		this.pathHelper = pathHelper;
		this.classHelper = classHelper;
	}
	
	public static Capturer create(ComponentSupplier componentSupplier) {
		return new Capturer(
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
		Collection<String> baseClassPaths,
		QuadConsumer<String, String, String, ByteBuffer>  javaClassConsumer,
		QuadConsumer<String, String, String, ByteBuffer>  resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
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
				} catch (Throwable exc) {
					throw Throwables.toRuntimeException(exc);				
				} finally {
					Thread.currentThread().setContextClassLoader(contextClassLoader);
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
			getStoreFunction(),
			getStoreFunction(),
			includeMainClass,
			continueToCaptureAfterSimulatorClassEndExecutionFor
		);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}
	
	QuadConsumer<String, String, String, ByteBuffer> getStoreFunction() {
		return (storeBasePath, resourceAbsolutePath, resourceRelativePath, resourceContent) -> {
			String finalPath = getStoreEntryBasePath(storeBasePath, resourceAbsolutePath, resourceRelativePath);
			FileSystemItem fileSystemItem = FileSystemItem.ofPath(finalPath + "/" + resourceRelativePath);
			if (!fileSystemItem.exists()) {
				Streams.store(fileSystemItem.getAbsolutePath(), resourceContent);
				logDebug("Resource {} has been stored to CLASSPATH {}", resourceRelativePath, finalPath);
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
	
	Consumer<Scan.ItemContext<FileInputStream>> getFileSystemEntryStorer(
		String destinationPath
	) {
		return (scannedItemContext) -> {
			String finalRelativePath = Strings.Paths.clean(scannedItemContext.getInput().getAbsolutePath()).replaceFirst(
				Strings.Paths.clean(scannedItemContext.getBasePath().getAbsolutePath()),
				""
			);
			Streams.store(finalRelativePath, scannedItemContext.getInput().toByteBuffer());
		};
	}
	
	
	Consumer<Scan.ItemContext<ZipInputStream.Entry>> getZipEntryStorer(
		String destinationPath
	) {
		return (scannedItemContext) -> {
			String finalRelativePath = Strings.Paths.clean(scannedItemContext.getInput().getAbsolutePath()).replaceFirst(
				Strings.Paths.clean(scannedItemContext.getBasePath().getAbsolutePath()),
				""
			);
			Streams.store(finalRelativePath, scannedItemContext.getInput().toByteBuffer());
		};
	}
		
	public static class Result implements Component {
		CompletableFuture<Void> findingTask;
		final Map<String, JavaClass> classPathClasses;
		Map<String, ByteBuffer> resources;
		Map<String, JavaClass> javaClasses;
		FileSystemItem store;
		QuadConsumer<String, String, String, ByteBuffer> javaClassConsumer;
		QuadConsumer<String, String, String, ByteBuffer> resourceConsumer;
		
		Result(
			Map<String, JavaClass> classPathClasses,
			QuadConsumer<String, String, String, ByteBuffer> javaClassConsumer,
			QuadConsumer<String, String, String, ByteBuffer> resourceConsumer
		) {
			this.javaClasses = new ConcurrentHashMap<>();
			this.classPathClasses = new ConcurrentHashMap<>();
			this.resources = new ConcurrentHashMap<>();
			this.classPathClasses.putAll(classPathClasses);
			this.javaClassConsumer = javaClassConsumer;
			this.resourceConsumer = resourceConsumer;
		}
		
		public JavaClass load(String className) {
			for (Map.Entry<String, JavaClass> entry : classPathClasses.entrySet()) {
				if (entry.getValue().getName().equals(className)) {
					JavaClass javaClass = entry.getValue();
					if (javaClassConsumer != null) {
						javaClassConsumer.accept(store.getAbsolutePath(), entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
					}
					javaClasses.put(entry.getKey(), javaClass);
					return entry.getValue();
				}
			}
			return null;
		}
		
		public Collection<JavaClass> loadAll(Collection<String> classesName) {
			Collection<JavaClass> javaClassAdded = new LinkedHashSet<>();
			for (Map.Entry<String, JavaClass> entry : classPathClasses.entrySet()) {
				if (classesName.contains(entry.getValue().getName())) {
					JavaClass javaClass = entry.getValue();
					javaClassAdded.add(javaClass);
					classesName.remove(javaClass.getName());
					if (javaClassConsumer != null) {
						javaClassConsumer.accept(store.getAbsolutePath(), entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
					}
					javaClasses.put(entry.getKey(), javaClass);
				}
			}
			return javaClassAdded;
		}
		
		public void putResource(FileSystemItem fileSystemItem, String resourceName) {
			if (fileSystemItem.isFile() && fileSystemItem.exists()) {
				if (resourceConsumer != null) {
		    		resourceConsumer.accept(store.getAbsolutePath(), fileSystemItem.getAbsolutePath(), resourceName, fileSystemItem.toByteBuffer());
		    		resources.put(resourceName, fileSystemItem.toByteBuffer());
		    	}
			}
		}
		
		JavaClass put(String className) {
			for (Map.Entry<String, JavaClass> entry : classPathClasses.entrySet()) {
				if (entry.getValue().getName().equals(className)) {
					if (javaClassConsumer != null) {
						JavaClass javaClass = entry.getValue();
						javaClassConsumer.accept(store.getAbsolutePath(), entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
					}
					javaClasses.put(entry.getKey(), entry.getValue());
					return entry.getValue();
				}
			}
			return null;
		}
		
		public Map<String, JavaClass> get() {
			return javaClasses;
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
			classPathClasses.clear();
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
