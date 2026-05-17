package sh.vork.ssh;

import com.sshtools.common.ssh.SshConnection;
import com.sshtools.server.vsession.UnsupportedCommandException;

public interface VirtualCommandFactory {

	String getName();
	
	VirtualCommand createCommand(String command, SshConnection con) throws UnsupportedCommandException;
}
