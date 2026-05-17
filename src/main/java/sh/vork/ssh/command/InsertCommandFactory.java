package sh.vork.ssh.command;

import com.sshtools.common.ssh.SshConnection;
import com.sshtools.server.vsession.UnsupportedCommandException;
import org.springframework.stereotype.Component;
import sh.vork.ssh.VirtualCommand;
import sh.vork.ssh.VirtualCommandFactory;

@Component
public class InsertCommandFactory implements VirtualCommandFactory {

    @Override
    public String getName() {
        return "insert";
    }

    @Override
    public VirtualCommand createCommand(String command, SshConnection con)
            throws UnsupportedCommandException {
        return new InsertCommand();
    }
}
