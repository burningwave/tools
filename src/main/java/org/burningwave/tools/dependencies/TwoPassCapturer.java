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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.burningwave.ManagedLogger;
import org.burningwave.Throwables;
import org.burningwave.core.Strings;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ClassCriteria;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.Classes;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.hunter.ByteCodeHunter;
import org.burningwave.core.classes.hunter.ClassPathHunter;
import org.burningwave.core.classes.hunter.SearchConfig;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileScanConfig;
import org.burningwave.core.io.FileSystemHelper;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.FileSystemScanner;
import org.burningwave.core.io.PathHelper;
import org.burningwave.tools.jvm.LowLevelObjectsHandler;


public class TwoPassCapturer extends Capturer {
	ClassPathHunter classPathHunter;
	private TwoPassCapturer(
		FileSystemScanner fileSystemScanner,
		PathHelper pathHelper,
		ByteCodeHunter byteCodeHunter,
		ClassPathHunter classPathHunter,
		ClassHelper classHelper,
		LowLevelObjectsHandler lowLevelObjectsHandler
	) {
		super(fileSystemScanner, pathHelper, byteCodeHunter, classHelper, lowLevelObjectsHandler);
		this.classPathHunter = classPathHunter;
	}
	
	public static TwoPassCapturer create(ComponentSupplier componentSupplier) {
		return new TwoPassCapturer(
			componentSupplier.getFileSystemScanner(),
			componentSupplier.getPathHelper(),
			componentSupplier.getByteCodeHunter(),
			componentSupplier.getClassPathHunter(),
			componentSupplier.getClassHelper(),
			LowLevelObjectsHandler.create(componentSupplier)
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
		Collection<String> _baseClassPaths,
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		final Result result = new Result(
			this.fileSystemScanner,
				javaClass -> true,
				fileSystemItem -> true
		);
		Collection<String> baseClassPaths = new LinkedHashSet<>(_baseClassPaths);
		baseClassPaths.addAll(additionalClassPaths);
		final AtomicBoolean recursiveFlagWrapper = new AtomicBoolean(recursive);
		result.findingTask = CompletableFuture.runAsync(() -> {
			try (Sniffer resourceSniffer = new Sniffer(null).init(
					!recursiveFlagWrapper.get(),
					fileSystemScanner,
					classHelper,
					lowLevelObjectsHandler,
					baseClassPaths,
					result.javaClassFilter,
					result.resourceFilter,
					resourceConsumer
				)
			) {	
				if (!recursiveFlagWrapper.get()) {
					Throwable resourceNotFoundException = null;
					do {
						try {
							mainClass.getMethod("main", String[].class).invoke(null, (Object)new String[]{});
							resourceNotFoundException = null;
							if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
								Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
							}
						} catch (Throwable exc) {
							Collection<String> penultimateNotFoundClasses = resourceNotFoundException != null?
								Classes.retrieveNames(resourceNotFoundException) : 
								new LinkedHashSet<>();
							resourceNotFoundException = exc;
							Collection<String> currentNotFoundClass = Classes.retrieveNames(resourceNotFoundException);
							if (!currentNotFoundClass.isEmpty()) {
								if (!currentNotFoundClass.containsAll(penultimateNotFoundClasses)) {
									try {
										for (JavaClass javaClass : resourceSniffer.consumeClasses(currentNotFoundClass)) {
											logDebug("Searching for {}", currentNotFoundClass);
											classHelper.loadOrUploadClass(javaClass, resourceSniffer.threadContextClassLoader);
										}
									} catch (Throwable exc2) {
										logError("Exception occurred", exc2);
										throw Throwables.toRuntimeException(exc2);				
									}
								} else {
									recursiveFlagWrapper.set(true);
									resourceNotFoundException = null;
								}
							} else {
								logError("Exception occurred", exc);
								throw Throwables.toRuntimeException(exc);
							}	
						}
					} while (resourceNotFoundException != null);
					
				} else {
					try {
						Class<?> cls = classHelper.loadOrUploadClass(mainClass, resourceSniffer);
						cls.getMethod("main", String[].class).invoke(null, (Object)new String[]{});
						if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
							Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
						}
					} catch (Throwable exc) {
						logError("Exception occurred", exc);
						throw Throwables.toRuntimeException(exc);				
					}
				}
			}
			if (recursiveFlagWrapper.get()) {
				try {
					launchExternalCapturer(
						mainClass, result.getStore().getAbsolutePath(), baseClassPaths, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor
					);
				} catch (IOException | InterruptedException exc) {
					throw Throwables.toRuntimeException(exc);
				}
			}
			if (recursive && !includeMainClass) {	
				JavaClass mainJavaClass = result.getJavaClass(javaClass -> javaClass.getName().equals(mainClass.getName()));
				Collection<FileSystemItem> mainJavaClassesFiles =
					result.getResources(fileSystemItem -> 
						fileSystemItem.getAbsolutePath().endsWith(mainJavaClass.getPath())
					);
				FileSystemItem store = result.getStore();
				for (FileSystemItem fileSystemItem : mainJavaClassesFiles) {
					FileSystemHelper.delete(fileSystemItem.getAbsolutePath());
					fileSystemItem = fileSystemItem.getParent();
					while (fileSystemItem != null && !fileSystemItem.getAbsolutePath().equals(store.getAbsolutePath())) {
						if (fileSystemItem.getChildren().isEmpty()) {
							FileSystemHelper.delete(fileSystemItem.getAbsolutePath());
						} else {
							break;
						}
						fileSystemItem = fileSystemItem.getParent();
					}
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
        if (lowLevelObjectsHandler.retrieveBuiltinClassLoaderClass() != null) {
        	command.add("--add-exports");
        	command.add("java.base/jdk.internal.loader=ALL-UNNAMED");
        }
        command.add("-classpath");
        StringBuffer generatedClassPath = new StringBuffer("\"");
        Collection<String> classPathsToBeScanned = new LinkedHashSet<>(baseClassPaths);
        classPathsToBeScanned.remove(destinationPath);
        Set<String> classPaths = FileSystemItem.ofPath(destinationPath).getChildren().stream().map(
        	child -> child.getAbsolutePath()
        ).collect(Collectors.toSet());
        generatedClassPath.append(String.join(System.getProperty("path.separator"), classPaths));
        //Adding Burningwave to classpath
        ClassPathHunter.SearchResult searchResult = classPathHunter.findBy(
			SearchConfig.forPaths(
				pathHelper.getMainClassPaths()
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
        String classPathsToBeScannedParam = "\"" + String.join(System.getProperty("path.separator"), classPathsToBeScanned);
        if (additionalClassPaths != null && !additionalClassPaths.isEmpty()) {
        	classPathsToBeScannedParam += System.getProperty("path.separator") + String.join(System.getProperty("path.separator"), additionalClassPaths);
        }
        classPathsToBeScannedParam += "\"";
        command.add(classPathsToBeScannedParam);
        command.add(mainClass.getName());
        command.add("\"" + destinationPath + "\"");
        command.add(Boolean.valueOf(includeMainClass).toString());
        command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        Process process = processBuilder.inheritIO().start();
        
        process.waitFor();
	}
	
	public static void main(String[] args) throws ClassNotFoundException {
		try {
			Collection<String> paths = Arrays.asList(args[0].split(System.getProperty("path.separator")));
			String mainClassName = args[1];		
			String destinationPath = args[2];
			boolean includeMainClass = Boolean.valueOf(args[3]);
			long continueToCaptureAfterSimulatorClassEndExecutionFor = Long.valueOf(args[4]);
			Class<?> mainClass = Class.forName(mainClassName);
			TwoPassCapturer.getInstance().captureAndStore(
				mainClass, paths, destinationPath, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, false
			).waitForTaskEnding();
		} catch (Throwable exc) {
			ManagedLogger.Repository.getInstance().logError(TwoPassCapturer.class, "Exception occurred", exc);
		} finally {
			logReceivedParameters(args, 0);	
		}
	}
	
	private static void logReceivedParameters(String[] args, long wait) {
		try {
			String logs =
					"classpath: " + System.getProperty("java.class.path") + "\n" +
					"path to be scanned: " + 
						String.join(";",
							Arrays.asList(
								args[0].split(System.getProperty("path.separator"))
							)
						) + "\n" +
					"mainClassName: " + args[1] + "\n" +
					"destinationPath: " + args[2] + "\n" +
					"includeMainClass: " + args[3] + "\n" +
					"continueToCaptureAfterSimulatorClassEndExecutionFor: " + args[4];
			
			Files.write(Paths.get(args[2] + "\\params-" + UUID.randomUUID().toString() + ".txt"), logs.getBytes());
			ManagedLogger.Repository.getInstance().logDebug(TwoPassCapturer.class, "\n\n" + logs + "\n\n");
			String externalExecutor = FileSystemItem.ofPath(System.getProperty("java.home")).getAbsolutePath() + "/bin/java -classpath \"" +
				String.join(";",	
					FileSystemItem.ofPath(args[2]).getChildren().stream().map(fileSystemItem -> fileSystemItem.getAbsolutePath()).collect(Collectors.toList())
				) + "\" " + args[1];
			Files.write(Paths.get(args[2] + "\\executor-" + UUID.randomUUID().toString() + ".cmd"), externalExecutor.getBytes());
			if (wait > 0) {
				Thread.sleep(wait);
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}
	
	private static class Result extends Capturer.Result {
		FileSystemScanner fileSystemScanner;
		Function<JavaClass, Boolean> javaClassFilter;
		Function<FileSystemItem, Boolean> resourceFilter;

		Result(
			FileSystemScanner fileSystemScanner,
			Function<JavaClass, Boolean> javaClassFilter,
			Function<FileSystemItem, Boolean> resourceFilter
		) {
			this.fileSystemScanner = fileSystemScanner;
			this.javaClassFilter = javaClassFilter;
			this.resourceFilter = resourceFilter;
			this.javaClasses = null;
			this.resources = null;
		}
		
		@Override
		public Collection<JavaClass> getJavaClasses() {
			if (this.findingTask.isDone()) {
				if (this.javaClasses == null) {
					synchronized (this.toString() + "_" + "javaClasses") {
						if (this.javaClasses == null) {
							return this.javaClasses = retrieveJavaClasses();
						}
					}
				}
				return this.javaClasses;
			} else {
				return retrieveJavaClasses();
			}
		}
		
		private Collection<JavaClass> retrieveJavaClasses() {
			Collection<FileSystemItem> resources = getResources();
			return resources.stream().filter(resource -> 
				resource.getExtension().equals("class")
			).map(javaClassResource -> 
				JavaClass.create(javaClassResource.toByteBuffer())
			).filter(javaClass -> {
				logDebug(javaClass.getName());
				return javaClassFilter.apply(javaClass);
			}
			).collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));
		}
		
		@Override
		public Collection<FileSystemItem> getResources() {
			if (this.findingTask.isDone()) {
				if (this.resources == null) {
					synchronized (this.toString() + "_" + "resources") {
						if (this.resources == null) {
							return this.resources = retrieveResources();
						}
					}
				}
				return this.resources;
			} else {
				return retrieveResources();
			}			
		}
		
		private Collection<FileSystemItem> retrieveResources() {
			Collection<FileSystemItem> resources = new CopyOnWriteArrayList<>();
			fileSystemScanner.scan(
				FileScanConfig.forPaths(store.getAbsolutePath()).toScanConfiguration(
					FileSystemItem.getResourceCollector(resources, resourceFilter)
				)
			);
			return resources;
		}
	}
	
	private static class LazyHolder {
		private static final TwoPassCapturer CAPTURER_INSTANCE = TwoPassCapturer.create(ComponentContainer.getInstance());
		
		private static TwoPassCapturer getCapturerInstance() {
			return CAPTURER_INSTANCE;
		}
	}
}
