package top.hcode.hoj.manager.oj.ai;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/** Isolated test helper, no database or network. */
public final class VideoFileLeaseProcess {
    public static void main(String[] args)throws Exception{
        try(FileChannel channel=FileChannel.open(Paths.get(args[0]),StandardOpenOption.WRITE);FileLock lease=channel.lock()){
            System.out.println("LOCKED");System.out.flush();System.in.read();
        }
    }
}
