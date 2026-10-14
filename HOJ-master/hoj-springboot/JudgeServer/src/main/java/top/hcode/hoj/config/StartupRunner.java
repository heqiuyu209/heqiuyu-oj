package top.hcode.hoj.config;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.hcode.hoj.dao.JudgeServerEntityService;
import top.hcode.hoj.pojo.entity.judge.JudgeServer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashMap;


/**
 * @Author: Himit_ZH
 * @Date: 2021/2/19 22:11
 * @Description:项目启动加载类，启动完毕将该判题机加入到redis里面
 */
@Component
@Slf4j
public class StartupRunner implements CommandLineRunner {

    @Value("${hoj-judge-server.max-task-num}")
    private Integer maxTaskNum;

    @Value("${hoj-judge-server.remote-judge.max-task-num}")
    private Integer maxRemoteTaskNum;

    @Value("${hoj-judge-server.remote-judge.open}")
    private Boolean openRemoteJudge;

    @Value("${hoj-judge-server.name}")
    private String name;

    @Value("${hoj-judge-server.ip}")
    private String ip;

    @Value("${hoj-judge-server.port}")
    private Integer port;

    private static final int cpuNum = Runtime.getRuntime().availableProcessors();

    @Autowired
    private JudgeServerEntityService judgeServerEntityService;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

    @Override
    @Transactional
    public void run(String... args) throws Exception {

//        log.info("IP  of the current judge server:" + ip);
//        log.info("Port of the current judge server:" + port);

        if (maxTaskNum == -1) {
            maxTaskNum = cpuNum + 1;
        }

        if ("-1".equals(ip)) {
            String registeredIp = nacosDiscoveryProperties.getIp();

            if (registeredIp == null || registeredIp.trim().isEmpty()) {
                throw new IllegalStateException("判题机自动注册地址为空");
            }

            InetAddress address = InetAddress.getByName(registeredIp);

            if (!(address instanceof Inet4Address)
                    || address.isLoopbackAddress()
                    || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalStateException(
                        "判题机自动注册地址不可用: " + registeredIp);
            }

            ip = registeredIp;
        }

        log.info("Judge server registration address: {}:{}", ip, port);

        UpdateWrapper<JudgeServer> judgeServerQueryWrapper = new UpdateWrapper<>();
        judgeServerQueryWrapper.eq("ip", ip).eq("port", port);
        judgeServerEntityService.remove(judgeServerQueryWrapper);
        boolean isOk1 = judgeServerEntityService.save(new JudgeServer()
                .setCpuCore(cpuNum)
                .setIp(ip)
                .setPort(port)
                .setUrl(ip + ":" + port)
                .setMaxTaskNumber(maxTaskNum)
                .setIsRemote(false)
                .setName(name));
        boolean isOk2 = true;
        if (openRemoteJudge) {
            if (maxRemoteTaskNum == -1) {
                maxRemoteTaskNum = cpuNum * 2 + 1;
            }
            isOk2 = judgeServerEntityService.save(new JudgeServer()
                    .setCpuCore(cpuNum)
                    .setIp(ip)
                    .setPort(port)
                    .setUrl(ip + ":" + port)
                    .setMaxTaskNumber(maxRemoteTaskNum)
                    .setIsRemote(true)
                    .setName(name));
        }

        if (!isOk1 || !isOk2) {
            log.error("初始化判题机信息到数据库失败，请重新启动试试！");
        } else {
            HashMap<String, Object> judgeServerInfo = judgeServerEntityService.getJudgeServerInfo();
            log.info("HOJ-JudgeServer had successfully started! The judge config and sandbox config Info:" + judgeServerInfo);
        }

    }

}