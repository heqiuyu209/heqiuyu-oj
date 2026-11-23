package top.hcode.hoj.manager.oj.external;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Holds the actual job row until MySQL reports that this callback is waiting. */
final class FetchLeaseLockTestSupport {
    static void whileJobLocked(JdbcTemplate jdbc, JdbcTemplate admin, String table, Runnable callback, Runnable advance) throws Exception {
        assertTrue(table.matches("external_nowcoder_(context|practice)_job|remote_source_job|remote_problem_import"));
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        ExecutorService worker=Executors.newSingleThreadExecutor();
        AtomicLong connection=new AtomicLong();CountDownLatch started=new CountDownLatch(1);
        AtomicReference<Future<?>> pending=new AtomicReference<>();
        try {
            tx.execute(held->{
                jdbc.queryForList("SELECT * FROM "+table+("remote_source_job".equals(table)?" WHERE source_name='CODEFORCES_PROBLEMS'":"")+" FOR UPDATE");
                pending.set(worker.submit(()->tx.execute(waiting->{
                    connection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                    started.countDown();callback.run();return null;
                })));
                try {
                    assertTrue(started.await(5,TimeUnit.SECONDS));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                    while(System.nanoTime()<deadline){
                        blocked=admin.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=? AND trx_state='LOCK WAIT'",Integer.class,connection.get())==1;
                        if(blocked)break;Thread.sleep(25);
                    }
                    assertTrue(blocked,"The callback must actually be waiting for the job row");
                }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
                advance.run();return null;
            });
            pending.get().get(10,TimeUnit.SECONDS);
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
