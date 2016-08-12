package com.cwctravel.eclipse.plugins.dependencies.resolvers.tfs;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.BundleContext;

public class TFSDependenciesResolverPlugin extends Plugin {
	public static final String ID = "com.cwctravel.eclipse.plugins.dependencies.resolvers.tfs";

	private static BundleContext context;

	private static TFSDependenciesResolverPlugin instance;

	public static TFSDependenciesResolverPlugin getInstance() {
		return instance;
	}

	public TFSDependenciesResolverPlugin() {
		instance = this;
	}

	static BundleContext getContext() {
		return context;
	}

	/*
	 * (non-Javadoc)
	 * @see org.osgi.framework.BundleActivator#start(org.osgi.framework.BundleContext)
	 */
	@Override
	public void start(BundleContext bundleContext) throws Exception {
		super.start(bundleContext);
		TFSDependenciesResolverPlugin.context = bundleContext;
	}

	/*
	 * (non-Javadoc)
	 * @see org.osgi.framework.BundleActivator#stop(org.osgi.framework.BundleContext)
	 */
	@Override
	public void stop(BundleContext bundleContext) throws Exception {
		super.stop(bundleContext);
		TFSDependenciesResolverPlugin.context = null;
	}

	public static void log(int severity, String message, Throwable t) {
		getInstance().getLog().log(new Status(severity, ID, message, t));
	}
}
