/*
 * This file is part of Burningwave Core.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/core
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Roberto Gentili
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
package org.burningwave.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.burningwave.Throwables;
import org.burningwave.core.Component;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.MemoryClassLoader;
import org.burningwave.core.classes.hunter.ByteCodeHunter;
import org.burningwave.core.classes.hunter.ByteCodeHunter.SearchResult;
import org.burningwave.core.classes.hunter.SearchConfig;
import org.burningwave.core.io.ByteBufferInputStream;
import org.burningwave.core.io.ByteBufferOutputStream;
import org.burningwave.core.io.FileOutputStream;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;
import org.burningwave.core.io.Streams;
import org.burningwave.core.jvm.LowLevelObjectsHandler;


public class DependenciesCapturer implements Component {
	private ByteCodeHunter byteCodeHunter;
	private LowLevelObjectsHandler lowLevelObjectsHandler;
	private PathHelper pathHelper;
	private ClassHelper classHelper;
	
	private DependenciesCapturer(LowLevelObjectsHandler lowLevelObjectsHandler, PathHelper pathHelper, ByteCodeHunter byteCodeHunter, ClassHelper classHelper) {
		this.lowLevelObjectsHandler = lowLevelObjectsHandler;
		this.byteCodeHunter = byteCodeHunter;
		this.pathHelper = pathHelper;
		this.classHelper = classHelper;
	}
	
	public static DependenciesCapturer create(ComponentSupplier componentSupplier) {
		return new DependenciesCapturer(
			componentSupplier.getLowLevelObjectsHandler(),
			componentSupplier.getPathHelper(),
			componentSupplier.getByteCodeHunter(),
			componentSupplier.getClassHelper()
		);
	}
	
	public static DependenciesCapturer getInstance() {
		return LazyHolder.getDependeciesCapturerInstance();
	}
	
	public Result capture(
		Class<?> simulatorClass,
		Collection<String> baseClassPaths,
		Consumer<JavaClass> javaClassConsumer,
		BiConsumer<String, ByteBuffer> resourceConsumer,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		final Result result;
		try (SearchResult searchResult = byteCodeHunter.findBy(
			SearchConfig.forPaths(
				baseClassPaths
			)
		)) {
			result = new Result(searchResult.getClassesFlatMap(), javaClassConsumer, resourceConsumer);
		}
		Set<String> classesNameToBeExcluded = lowLevelObjectsHandler.retrieveAllLoadedClasses(
			this.getClass().getClassLoader()
		).stream().map(clsss -> 
			clsss.getName()).collect((Collectors.toSet())
		);
		result.findingTask = CompletableFuture.runAsync(() -> {
			Class<?> cls;
			try (MemoryClassLoader memoryClassLoader = new MemoryClassLoader(null, classHelper) {
				@Override
				public void addLoadedCompiledClass(String name, ByteBuffer byteCode) {
					super.addLoadedCompiledClass(name, byteCode);
					if (!name.equals(simulatorClass.getName())) {
						result.put(name);
					}
					classesNameToBeExcluded.remove(name);
				};
				
				@Override
			    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			    	Class<?> cls = super.loadClass(name, resolve);
			    	if (!name.equals(simulatorClass.getName())) {
						result.load(name);	
					}
			    	classesNameToBeExcluded.remove(name);
			    	return cls;
			    }
				
				@Override
			    public InputStream getResourceAsStream(String name) {
			    	InputStream inputStream = super.getResourceAsStream(name);
			    	ByteBufferOutputStream bBOS = new ByteBufferOutputStream();
			    	Streams.copy(inputStream, bBOS);
			    	result.putResource(name, bBOS.toByteBuffer());			    	
			    	return super.getResourceAsStream(name);
			    }
				
			}) {
				for (Entry<String, JavaClass> entry : result.classPathClasses.entrySet()) {
					JavaClass javaClass = entry.getValue();
					memoryClassLoader.addCompiledClass(javaClass.getName(), javaClass.getByteCode());
				}
				try {
					cls = classHelper.loadOrUploadClass(simulatorClass, memoryClassLoader);
					cls.getMethod("main", String[].class).invoke(null, (Object)new String[]{});
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
				} catch (Throwable exc) {					
					Set<String> allLoadedClasses = lowLevelObjectsHandler.retrieveAllLoadedClasses(
						this.getClass().getClassLoader()
					).stream().map(clsss -> 
						clsss.getName()).collect((Collectors.toSet())
					);
					allLoadedClasses.removeAll(classesNameToBeExcluded);
					result.loadAll(allLoadedClasses);
					try {
						classesNameToBeExcluded.addAll(allLoadedClasses);
						simulatorClass.getMethod("main", String[].class).invoke(null, (Object)new String[]{});
						if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
							Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
						}
						allLoadedClasses = lowLevelObjectsHandler.retrieveAllLoadedClasses(
							this.getClass().getClassLoader()
						).stream().map(clsss -> 
							clsss.getName()).collect((Collectors.toSet())
						);
						allLoadedClasses.removeAll(classesNameToBeExcluded);
						result.loadAll(allLoadedClasses);
					} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException
							| NoSuchMethodException | SecurityException | InterruptedException e) {
						throw Throwables.toRuntimeException(exc);
					}					
				}
			}
		});
		return result;
	}
	
	public Result captureAndStore(
		Class<?> simulatorClass,
		String destinationPath,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		return captureAndStore(simulatorClass, pathHelper.getMainClassPaths(), destinationPath, continueToCaptureAfterSimulatorClassEndExecutionFor);
	}
	
	public Result captureAndStore(
		Class<?> simulatorClass,
		Collection<String> baseClassPaths,
		String destinationPath,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		Result dependencies = capture(
			simulatorClass,
			baseClassPaths, (javaClass) -> 
				javaClass.storeToClassPath(destinationPath),
			(resourceName, resourceContent) -> {
				try (
					ByteBufferInputStream inputStream = new ByteBufferInputStream(resourceContent);
					FileOutputStream outputStream = FileOutputStream.create(new File(destinationPath + "/" + resourceName))
				) {
					Streams.copy(inputStream, outputStream);
				} catch (IOException exc) {
					logError("Could not persist resource resourceName", exc);
				}
			},
			continueToCaptureAfterSimulatorClassEndExecutionFor
		);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}
		
	public static class Result implements Component {
		private CompletableFuture<Void> findingTask;
		private final Map<String, JavaClass> classPathClasses;
		private Map<String, ByteBuffer> resources;
		private Map<String, JavaClass> result;
		private FileSystemItem store;
		private Consumer<JavaClass> javaClassConsumer;
		private BiConsumer<String, ByteBuffer> resourceConsumer;
		
		private Result(Map<String, JavaClass> classPathClasses, Consumer<JavaClass> javaClassConsumer, BiConsumer<String, ByteBuffer> resourceConsumer) {
			this.result = new ConcurrentHashMap<>();
			this.resources = new ConcurrentHashMap<>();
			this.classPathClasses = new ConcurrentHashMap<>();
			this.classPathClasses.putAll(classPathClasses);
			this.javaClassConsumer = javaClassConsumer;
			this.resourceConsumer = resourceConsumer;
		}
		
		public JavaClass load(String className) {
			for (Map.Entry<String, JavaClass> entry : classPathClasses.entrySet()) {
				if (entry.getValue().getName().equals(className)) {
					JavaClass javaClass = entry.getValue();
					result.put(entry.getKey(), javaClass);
					if (javaClassConsumer != null) {
						logDebug("Storing class " + javaClass);
						javaClassConsumer.accept(javaClass);
					}
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
					result.put(entry.getKey(), javaClass);
					javaClassAdded.add(javaClass);
					classesName.remove(javaClass.getName());
					if (javaClassConsumer != null) {
						logDebug("Storing class " + javaClass);
						javaClassConsumer.accept(javaClass);
					}
				}
			}
			return javaClassAdded;
		}

		public void putResource(String name, ByteBuffer bytes) {
			resources.put(name, bytes);
			if (resourceConsumer != null) {
	    		resourceConsumer.accept(name, Streams.shareContent(bytes));
	    	}
		}

		private JavaClass put(String className) {
			for (Map.Entry<String, JavaClass> entry : classPathClasses.entrySet()) {
				if (entry.getValue().getName().equals(className)) {
					result.put(entry.getKey(), entry.getValue());
					if (javaClassConsumer != null) {
						javaClassConsumer.accept(entry.getValue());
					}
					return entry.getValue();
				}
			}
			return null;
		}
		
		public Map<String, JavaClass> get() {
			return result;
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
			result.clear();
			result = null;
			store = null;
		}
	}
	
	private static class LazyHolder {
		private static final DependenciesCapturer DEPENDECIES_CAPTURER_INSTANCE = DependenciesCapturer.create(ComponentContainer.getInstance());
		
		private static DependenciesCapturer getDependeciesCapturerInstance() {
			return DEPENDECIES_CAPTURER_INSTANCE;
		}
	}
}
