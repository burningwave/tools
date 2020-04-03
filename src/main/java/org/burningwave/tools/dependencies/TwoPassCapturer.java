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

import static org.burningwave.core.assembler.StaticComponentContainer.FileSystemHelper;
import static org.burningwave.core.assembler.StaticComponentContainer.LowLevelObjectsHandler;
import static org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggersRepository;
import static org.burningwave.core.assembler.StaticComponentContainer.Paths;
import static org.burningwave.core.assembler.StaticComponentContainer.Throwables;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ByteCodeHunter;
import org.burningwave.core.classes.ClassCriteria;
import org.burningwave.core.classes.ClassPathHunter;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.SearchConfig;
import org.burningwave.core.function.ThrowingSupplier;
import org.burningwave.core.function.TriConsumer;
import org.burningwave.core.io.FileScanConfig;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.FileSystemScanner;
import org.burningwave.core.io.PathHelper;


public class TwoPassCapturer extends Capturer {
	ClassPathHunter classPathHunter;
	PathHelper pathHelper;
	private TwoPassCapturer(
		FileSystemScanner fileSystemScanner,
		PathHelper pathHelper,
		ByteCodeHunter byteCodeHunter,
		ClassPathHunter classPathHunter
	) {
		super(fileSystemScanner, byteCodeHunter);
		this.pathHelper = pathHelper;
		this.classPathHunter = classPathHunter;
	}
	
	public static TwoPassCapturer create(ComponentSupplier componentSupplier) {
		return new TwoPassCapturer(
			componentSupplier.getFileSystemScanner(),
			componentSupplier.getPathHelper(),
			componentSupplier.getByteCodeHunter(),
			componentSupplier.getClassPathHunter()
		);
	}
	
	public static TwoPassCapturer getInstance() {
		return LazyHolder.getCapturerInstance();
	}
	
	@Override
	public Result capture(
		String mainClassName,
		String[] mainMethodAruments,
		Collection<String> baseClassPaths,
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		return capture(mainClassName, mainMethodAruments, baseClassPaths, resourceConsumer, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, true);
	}
	
	public Result capture(
		String mainClassName,
		String[] mainMethodAruments,
		Collection<String> baseClassPaths,
		TriConsumer<String, String, ByteBuffer> resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		LowLevelObjectsHandler.disableIllegalAccessLogger();
		final Result result = new Result(
			this.fileSystemScanner,
				javaClass -> true,
				fileSystemItem -> true
		);
		result.findingTask = CompletableFuture.runAsync(() -> {
			try (Sniffer resourceSniffer = new Sniffer(null).init(
					!recursive,
					fileSystemScanner,
					baseClassPaths,
					result.javaClassFilter,
					result.resourceFilter,
					resourceConsumer
				)
			) {	
				ThrowingSupplier<Class<?>, ClassNotFoundException> mainClassSupplier = recursive ?
					() -> Class.forName(mainClassName, false, resourceSniffer):
					() -> Class.forName(mainClassName);
				try {
					mainClassSupplier.get().getMethod("main", String[].class).invoke(null, (Object)mainMethodAruments);
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
				} catch (Throwable exc) {
					logError("Exception occurred", exc);
					throw Throwables.toRuntimeException(exc);				
				}
			}
			if (recursive) {
				try {
					launchExternalCapturer(
						mainClassName, mainMethodAruments, result.getStore().getAbsolutePath(), baseClassPaths, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor
					);
				} catch (IOException | InterruptedException exc) {
					throw Throwables.toRuntimeException(exc);
				}
			}
			if (recursive && !includeMainClass) {	
				JavaClass mainJavaClass = result.getJavaClass(javaClass -> javaClass.getName().equals(mainClassName));
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
			LowLevelObjectsHandler.enableIllegalAccessLogger();
		});
		return result;
	}
	
	private Result captureAndStore(
		String mainClassName,
		String[] mainMethodAruments,
		Collection<String> baseClassPaths,
		String destinationPath,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		Result dependencies = capture(
			mainClassName,
			mainMethodAruments,
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
		String mainClassName,
		String[] mainMethodAruments,
		String destinationPath,
		Collection<String> baseClassPaths,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) throws IOException, InterruptedException {        
        //Excluding Burningwave from next process classpath
        Set<String> classPaths = FileSystemItem.ofPath(destinationPath).getChildren(fileSystemItem -> 
        	!fileSystemItem.getAbsolutePath().endsWith(BURNINGWAVE_CLASSES_RELATIVE_DESTINATION_PATH)
        ).stream().map(child ->
        	child.getAbsolutePath()
        ).collect(Collectors.toSet());
      
        //Adding Burningwave to next process scanning path
        ClassPathHunter.SearchResult searchResult = classPathHunter.findBy(
			SearchConfig.forPaths(
				pathHelper.getMainClassPaths()
			).by(
				ClassCriteria.create().className(clsName -> 
					clsName.equals(this.getClass().getName()) || clsName.equals(ComponentSupplier.class.getName())
				)
			)
    	);
        Collection<String> classPathsToBeScanned = new LinkedHashSet<>(baseClassPaths);
        classPathsToBeScanned.remove(destinationPath);
        Iterator<FileSystemItem> classPathIterator = searchResult.getClassPaths().iterator();
        while (classPathIterator.hasNext()) {
        	FileSystemItem classPath = classPathIterator.next();
        	if (!classPaths.contains(classPath.getAbsolutePath())) {
        		classPaths.add(classPath.getAbsolutePath());
	        	//classPathsToBeScanned.remove(classPath.getAbsolutePath());
        	}
        }
        ProcessBuilder processBuilder =
        	System.getProperty("os.name").toLowerCase().contains("windows")?
        		getProcessBuilderForWindows(
        			classPaths, classPathsToBeScanned, mainClassName, 
        			mainMethodAruments, destinationPath, includeMainClass, 
        			continueToCaptureAfterSimulatorClassEndExecutionFor
        		) : 
    			getProcessBuilderForUnix(
            		classPaths, classPathsToBeScanned, mainClassName, 
            		mainMethodAruments, destinationPath, includeMainClass, 
            		continueToCaptureAfterSimulatorClassEndExecutionFor
            	);
        Process process = processBuilder.start();
        process.waitFor();
	}
	
	private ProcessBuilder getProcessBuilderForWindows(
		Collection<String> classPaths, 
		Collection<String> classPathsToBeScanned,
		String mainClassName,
		String[] mainMethodAruments,
		String destinationPath,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) throws IOException {
		 String javaExecutablePath = System.getProperty("java.home") + "/bin/java";
		List<String> command = new LinkedList<String>();
        command.add(Paths.clean(javaExecutablePath));
        command.add("-classpath");
        StringBuffer generatedClassPath = new StringBuffer();
        generatedClassPath.append("\"");
        if (!classPaths.isEmpty()) {
        	generatedClassPath.append(String.join(System.getProperty("path.separator"), classPaths));
        }
        generatedClassPath.append("\"");
        command.add(generatedClassPath.toString());
        command.add(this.getClass().getName());
        command.add("\"" + String.join(System.getProperty("path.separator"), classPathsToBeScanned) + "\"");
        command.add(mainClassName);
        command.add("\"" + destinationPath + "\"");
        command.add(Boolean.valueOf(includeMainClass).toString());
        command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
        command.addAll(Arrays.asList(mainMethodAruments));
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        return processBuilder.inheritIO();
	}
	
	private ProcessBuilder getProcessBuilderForUnix(
		Collection<String> classPaths, 
		Collection<String> classPathsToBeScanned,
		String mainClassName,
		String[] mainMethodAruments,
		String destinationPath,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) throws IOException {
		List<String> command = new LinkedList<String>();
		String javaExecutablePath = Paths.clean(System.getProperty("java.home") + "/bin/java");
        command.add("\"" + javaExecutablePath + "\"");
        command.add("-classpath");
        StringBuffer generatedClassPath = new StringBuffer();
        if (!classPaths.isEmpty()) {
        	generatedClassPath.append(String.join(System.getProperty("path.separator"), classPaths));
        }
        command.add(generatedClassPath.toString());
        command.add(this.getClass().getName());
        command.add(String.join(System.getProperty("path.separator"), classPathsToBeScanned));
        command.add(mainClassName);
        command.add(destinationPath);
        command.add(Boolean.valueOf(includeMainClass).toString());
        command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
        command.addAll(Arrays.asList(mainMethodAruments));
        ProcessBuilder processBuilder = new ProcessBuilder(command);

        return processBuilder.inheritIO();
	}
	
	public static void main(String[] args) throws ClassNotFoundException {
		String[] mainMethodArguments = args.length > 5 ?
			Arrays.copyOfRange(args, 5, args.length): 
			new String[0];
		TwoPassCapturer capturer = TwoPassCapturer.getInstance();
		try {
			Collection<String> paths = Arrays.asList(args[0].split(System.getProperty("path.separator")));
			String mainClassName = args[1];		
			String destinationPath = args[2];
			boolean includeMainClass = Boolean.valueOf(args[3]);
			long continueToCaptureAfterSimulatorClassEndExecutionFor = Long.valueOf(args[4]);
			capturer.captureAndStore(
				mainClassName, mainMethodArguments, paths, destinationPath, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, false
			).waitForTaskEnding();
		} catch (Throwable exc) {
			ManagedLoggersRepository.logError(TwoPassCapturer.class, "Exception occurred", exc);
		} finally {
			String suffix = UUID.randomUUID().toString();
			capturer.logReceivedParameters(args, 0, suffix);
			capturer.createExecutor(args[2], args[1], mainMethodArguments, suffix);
		}
	}
	
	private void logReceivedParameters(String[] args, long wait, String fileSuffix) {
		try {
			
			String logs =String.join("\n",
				"classpath: " + System.getProperty("java.class.path"),
				"path to be scanned: " + String.join(System.getProperty("path.separator"),
					args[0].split(System.getProperty("path.separator"))
				),
				"mainClassName: " + args[1],
				"destinationPath: " + args[2],
				"includeMainClass: " + args[3],
				"continueToCaptureAfterSimulatorClassEndExecutionFor: " + args[4],
				(args.length > 5 ? "arguments: " + String.join(", ",
					Arrays.copyOfRange(args, 5, args.length)
				) : "")
			);
			
			Files.write(java.nio.file.Paths.get(args[2] + "/params-" + fileSuffix + ".txt"), logs.getBytes());
			ManagedLoggersRepository.logDebug(TwoPassCapturer.class, "\n\n" + logs + "\n\n");
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
			if (javaClasses != null) {
				return javaClasses;
			} else {
				return loadResourcesAndJavaClasses().getValue();
			}
		}
		
		@Override
		public Collection<FileSystemItem> getResources() {
			if (resources != null) {
				return resources;
			} else {
				return loadResourcesAndJavaClasses().getKey();
			}
		}
		
		public Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> loadResourcesAndJavaClasses() {
			Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> itemsFound = null;
			if (this.findingTask.isDone()) {
				if (this.resources == null) {
					synchronized (this.toString() + "_" + "resources") {
						if (this.resources == null) {
							itemsFound = retrieveResources();
							this.resources = itemsFound.getKey();
							this.javaClasses = itemsFound.getValue();
							return itemsFound;
						}
					}
				}
			}
			return retrieveResources();	
		}
		
		private Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> retrieveResources() {
			Collection<FileSystemItem> resources = ConcurrentHashMap.newKeySet();
			Collection<JavaClass> javaClasses = ConcurrentHashMap.newKeySet();
			Map.Entry<Collection<FileSystemItem>, Collection<JavaClass>> itemsFound = new 
				AbstractMap.SimpleEntry<>(resources, javaClasses);
			fileSystemScanner.scan(
				FileScanConfig.forPaths(
					store.getChildren().stream().filter(fileSystemItem ->
						fileSystemItem.isFolder()
					).map(fileSystemItem ->
						fileSystemItem.getAbsolutePath()
					).collect(
						Collectors.toSet()
					)
				).toScanConfiguration(
					FileSystemItem.getFilteredConsumerForFileSystemScanner(
						(fileSystemItem) -> resourceFilter.apply(fileSystemItem),
						(fileSystemItem) -> {
							resources.add(fileSystemItem);
							if (fileSystemItem.getExtension().equals("class")) {
								javaClasses.add(JavaClass.create(fileSystemItem.toByteBuffer()));
							}
						}
					)
				)
			);
			return itemsFound;
		}
	}
	
	private static class LazyHolder {
		private static final TwoPassCapturer CAPTURER_INSTANCE = TwoPassCapturer.create(ComponentContainer.getInstance());
		
		private static TwoPassCapturer getCapturerInstance() {
			return CAPTURER_INSTANCE;
		}
	}
}
