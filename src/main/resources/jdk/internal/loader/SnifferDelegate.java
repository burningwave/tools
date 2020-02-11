package jdk.internal.loader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;

import org.burningwave.Throwables;
import org.burningwave.core.Component;
import org.burningwave.tools.dependencies.Sniffer;


public class SnifferDelegate extends BuiltinClassLoader implements Component {
	private Sniffer sniffer;
	
	SnifferDelegate(String name, BuiltinClassLoader parent, URLClassPath ucp) {
		super(name, null, null);
	}
	
	public void init(Sniffer sniffer) {
		this.sniffer = sniffer;
	}
	
	@Override
	protected Class<?> loadClassOrNull(String cn, boolean resolve) {
		try {
			return sniffer._loadClass(cn, resolve);
		} catch (ClassNotFoundException exc) {
			throw Throwables.toRuntimeException(exc);
		}
	}
	
	@Override
	protected Class<?> loadClass(String cn, boolean resolve) throws ClassNotFoundException {
		return sniffer._loadClass(cn, resolve);
	}
	
	@Override
	public URL getResource(String name) {
		return sniffer.getResource(name);
	}
	
	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		return sniffer.getResources(name);
	}
	
    @Override
    public InputStream getResourceAsStream(String name) {
    	return sniffer.getResourceAsStream(name);
    }
}
