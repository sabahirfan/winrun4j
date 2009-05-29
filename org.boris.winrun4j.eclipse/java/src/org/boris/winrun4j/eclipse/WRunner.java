/*******************************************************************************
 * This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at 
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     Peter Smith
 *******************************************************************************/
package org.boris.winrun4j.eclipse;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jdi.Bootstrap;
import org.eclipse.jdt.debug.core.JDIDebugModel;
import org.eclipse.jdt.launching.AbstractVMRunner;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.SocketUtil;
import org.eclipse.jdt.launching.VMRunnerConfiguration;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.ListeningConnector;

public class WRunner extends AbstractVMRunner
{
    private IVMInstall vmInstall;
    private boolean debug;
    private ILaunchConfiguration launchConfig;

    public WRunner(ILaunchConfiguration configuration, IVMInstall vmInstall, String mode) {
        this.launchConfig = configuration;
        this.vmInstall = vmInstall;
        this.debug = ILaunchManager.DEBUG_MODE.equals(mode);
    }

    protected String getPluginIdentifier() {
        return WActivator.getIdentifier();
    }

    public void run(VMRunnerConfiguration configuration, ILaunch launch, IProgressMonitor monitor)
            throws CoreException {
        if (monitor == null) {
            monitor = new NullProgressMonitor();
        }

        IProgressMonitor subMonitor = new SubProgressMonitor(monitor, 1);
        try {
            subMonitor.beginTask("WinRun4J Debug Launch", 10);
            doRun(configuration, launch, subMonitor);
        } finally {
            subMonitor.done();
        }
    }

    public void doRun(VMRunnerConfiguration configuration, ILaunch launch, IProgressMonitor monitor)
            throws CoreException {
        Map ini = new HashMap();
        ini.put("main.class", configuration.getClassToLaunch());
        ini.put("vm.location", getJVMPath());
        String[] vmargs = configuration.getVMArguments();
        int port = -1;
        int offset = 1;
        if (debug) {
            port = SocketUtil.findFreePort();
            ini.put("vmarg.1", "-Xdebug");
            ini.put("vmarg.2", "-Xnoagent");
            ini.put("vmarg.3", "-Xrunjdwp:transport=dt_socket,suspend=y,address=" + port);
            offset = 4;
        }
        if (vmargs != null) {
            for (int i = 0; i < vmargs.length; i++) {
                ini.put("vmarg." + (i + offset), vmargs[i]);
            }
        }
        String[] cp = configuration.getClassPath();
        for (int i = 0; i < cp.length; i++) {
            ini.put("classpath." + (i + 1), cp[i]);
        }
        String wd = configuration.getWorkingDirectory();
        if (wd != null)
            ini.put("working.directory", wd);
        String[] args = configuration.getProgramArguments();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                ini.put("arg." + (i + 1), args[i]);
            }
        }
        ini.put("log.level", launchConfig.getAttribute(
                IWinRun4JLaunchConfigurationConstants.PROP_LOG_LEVEL, "info"));

        File launcher = null;
        File inf = null;

        try {
            monitor.subTask("Extracting launcher executable");
            launcher = extractLauncher();
            monitor.worked(1);
        } catch (IOException e) {
            abort("Could not generate INI file for launch", e, IStatus.ERROR);
        }

        try {
            monitor.subTask("Generating INI file...");
            inf = buildIniFile(ini);
            monitor.worked(1);
        } catch (IOException e) {
            abort("Could not generate INI file for launch", e, IStatus.ERROR);
        }

        String[] cmdLine = new String[] { launcher.getAbsolutePath(), "--WinRun4J:ExecuteINI",
                inf.getAbsolutePath() };

        if (monitor.isCanceled())
            return;

        // Debug listener setup
        ListeningConnector lc = getListeningConnector();
        Map m = lc.defaultArguments();
        if (debug) {
            Connector.IntegerArgument pa = (Connector.IntegerArgument) m.get("port"); //$NON-NLS-1$
            pa.setValue(port);
            try {
                lc.startListening(m);
            } catch (Throwable e) {
                monitor.done();
                abort("Error attaching debugger...", e, IStatus.ERROR);
            }
        }

        monitor.worked(1);

        File wdf = wd == null ? null : new File(wd);
        Process p = exec(cmdLine, wdf, configuration.getEnvironment());
        if (p == null) {
            return;
        }

        monitor.worked(1);

        // check for cancellation
        if (monitor.isCanceled()) {
            p.destroy();
            return;
        }

        IProcess process = newProcess(launch, p, renderProcessLabel(cmdLine),
                getDefaultProcessMap());
        process.setAttribute(IProcess.ATTR_CMDLINE, renderCommandLine(cmdLine));

        // Check for debugger
        if (debug) {
            try {
                VirtualMachine vm = lc.accept(m);
                JDIDebugModel.newDebugTarget(launch, vm, renderDebugTarget(configuration
                        .getClassToLaunch(), port), process, true, false, configuration
                        .isResumeOnStartup());
            } catch (Exception e) {
                monitor.done();
                abort("Error attaching debugger...", e, IStatus.ERROR);
            }
        }

        monitor.done();
    }

    private ListeningConnector getListeningConnector() {
        List connectors = Bootstrap.virtualMachineManager().listeningConnectors();
        for (int i = 0; i < connectors.size(); i++) {
            ListeningConnector c = (ListeningConnector) connectors.get(i);
            if ("com.sun.jdi.SocketListen".equals(c.name())) {
                return c;
            }
        }

        return null;
    }

    protected String renderDebugTarget(String classToRun, int host) {
        String format = "{0} at localhost:{1}";
        return MessageFormat.format(format, new String[] { classToRun, String.valueOf(host) });
    }

    private String renderCommandLine(String[] cmdLine) {
        return cmdLine[0];
    }

    private String renderProcessLabel(String[] cmdLine) {
        return cmdLine[0];
    }

    private File extractLauncher() throws IOException {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        File launcher = new File(tmpDir, WActivator.getVersionedIdentifier() + "-launcher.exe");
        launcher.deleteOnExit();
        if (launcher.exists()) {
            return launcher;
        }
        FileOutputStream fos = new FileOutputStream(launcher);
        InputStream is = WActivator.getBundleEntry("/launcher/WinRun4J.exe").openStream();
        IO.copy(is, fos, true);
        return launcher;
    }

    private File buildIniFile(Map ini) throws IOException {
        File f = File.createTempFile("winrun4j-", ".ini");
        f.deleteOnExit();
        PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f))));
        for (Iterator i = ini.keySet().iterator(); i.hasNext();) {
            String k = (String) i.next();
            String v = (String) ini.get(k);
            if (k != null && v != null) {
                pw.print(k);
                pw.print("=");
                pw.println(v);
            }
        }
        pw.flush();
        pw.close();
        return f;
    }

    private String getJVMPath() {
        File f = new File(vmInstall.getInstallLocation(), "bin" + File.separatorChar + "client"
                + File.separatorChar + "jvm.dll");
        if (!f.exists()) {
            f = new File(vmInstall.getInstallLocation(), "jre" + File.separatorChar + "bin"
                    + File.separatorChar + "client" + File.separatorChar + "jvm.dll");
        }
        return f.getAbsolutePath();
    }
}