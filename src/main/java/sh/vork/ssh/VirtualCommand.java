package sh.vork.ssh;

import java.io.IOException;

import com.sshtools.common.permissions.PermissionDeniedException;
import com.sshtools.server.vsession.ShellCommand;
import com.sshtools.server.vsession.UsageException;
import com.sshtools.server.vsession.VirtualConsole;

public abstract class VirtualCommand extends ShellCommand {

	public VirtualCommand(String name, String subsystem, String signature, String description) {
		super(name, subsystem, signature, description);
	}

	@Override
	public void run(String[] args, VirtualConsole console)
			throws IOException, PermissionDeniedException, UsageException {
		doRun(args, console);
	}

	protected abstract void doRun(String[] args, VirtualConsole console) throws IOException, PermissionDeniedException;

}
