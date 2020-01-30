package org.burningwave.tools;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.MemoryClassLoader;
import org.burningwave.core.io.FileSystemItem;

public class ResourceSniffer extends MemoryClassLoader {
	private ClassLoader mainClassLoader;
	private Consumer<String> classNameConsumer;
	private BiConsumer<FileSystemItem, String> resourceConsumer;
	
	protected ResourceSniffer(
		ClassLoader mainClassLoader,
		ClassHelper classHelper,
		Consumer<String> classNameConsumer,
		BiConsumer<FileSystemItem, String> resourceConsumer
	) {
		super(null, classHelper);
		this.mainClassLoader = mainClassLoader;
		this.classNameConsumer = classNameConsumer;
		this.resourceConsumer = resourceConsumer;
	}
	
	@Override
	public void addLoadedCompiledClass(String name, ByteBuffer byteCode) {
		super.addLoadedCompiledClass(name, byteCode);
		classNameConsumer.accept(name);
	};
	
	@Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    	Class<?> cls = super.loadClass(name, resolve);
    	classNameConsumer.accept(name);
    	return cls;
    }
	 @Override
	public URL getResource(String name) {
		URL resourceURL = mainClassLoader.getResource(name);
		if (resourceURL != null) {
			resourceConsumer.accept(FileSystemItem.ofPath(resourceURL), name);
		}
		return resourceURL;
	}
	
	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		Enumeration<URL> resourcesURL = mainClassLoader.getResources(name);
		while (resourcesURL.hasMoreElements()) {
			URL resourceURL = resourcesURL.nextElement();
			resourceConsumer.accept(FileSystemItem.ofPath(resourceURL), name);
		}
		return mainClassLoader.getResources(name);
	}
    
    @Override
    public InputStream getResourceAsStream(String name) {
    	Function<String, InputStream> inputStreamRetriever =
    			name.endsWith(".class") ? 
    				super::getResourceAsStream :
    				mainClassLoader::getResourceAsStream;
    	
    	InputStream inputStream = inputStreamRetriever.apply(name);
    	if (inputStream != null) {
    		getResource(name);
    	}
    	return inputStreamRetriever.apply(name);
    }
}
