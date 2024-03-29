package com.jd.blockchain.runtime.boot;

public class HomeContext {

	private String homeDir;

	private String runtimeDir;

	private String libsDir;

	private boolean productMode;

	private ClassLoader libsClassLoader;

	private ClassLoader systemClassLoader;
	
	private String[] startingArgs;

	public HomeContext(ClassLoader libsClassLoader, ClassLoader systemClassLoader, String homeDir, String runtimeDir,
			String libsDir, boolean productMode, String[] startingArgs) {
		this.libsClassLoader = libsClassLoader;
		this.systemClassLoader = systemClassLoader;
		this.homeDir = homeDir;
		this.runtimeDir = runtimeDir;
		this.libsDir = libsDir;
		this.productMode = productMode;
		this.startingArgs = startingArgs;
	}

	public String getHomeDir() {
		return homeDir;
	}

	public String getRuntimeDir() {
		return runtimeDir;
	}

	public ClassLoader getLibsClassLoader() {
		return libsClassLoader;
	}

	public ClassLoader getSystemClassLoader() {
		return systemClassLoader;
	}

	public boolean isProductMode() {
		return productMode;
	}

	public String[] getStartingArgs() {
		return startingArgs;
	}

	public String getLibsDir() {
		return libsDir;
	}
}
