package sh.vork.ssh.command;

import com.sshtools.common.ssh.SshConnection;
import com.sshtools.server.vsession.UnsupportedCommandException;
import org.springframework.stereotype.Component;
import sh.vork.ssh.VirtualCommand;
import sh.vork.ssh.VirtualCommandFactory;

@Component
public class UpdateCommandFactory implements VirtualCommandFactory {

    @Override
    public String getName() {
        return "update";
    }

    @Override
    public VirtualCommand createCommand(String command, SshConnection con)
            throws UnsupportedCommandException {
        return new UpdateCommand();
    }
}
