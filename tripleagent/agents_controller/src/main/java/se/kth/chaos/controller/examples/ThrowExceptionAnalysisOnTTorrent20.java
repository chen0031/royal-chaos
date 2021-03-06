package se.kth.chaos.controller.examples;

import se.kth.chaos.controller.AgentsController;
import se.kth.chaos.controller.JMXMonitoringTool;

import java.io.*;
import java.util.*;

public class ThrowExceptionAnalysisOnTTorrent20 {
    public static void main(String[] args) {
        Process process = null;
        String rootPath = "ttorrent_evaluation_2.0";
        String javaagentPath = System.getProperty("user.dir") + "/../perturbation_agent/target/foagent-perturbation-jar-with-dependencies.jar";
        String endingPattern = "BitTorrent client signing off";
        String threadName = "ttorrent-2.0-client.jar";
        String torrentFile = "CentOS-7-x86_64-NetInstall-1810.torrent";
        String analysisFilter = "com/turn/ttorrent";
        String targetCsv = "perturbationPointsList.csv";
        int timeout = 240;
        String osName = System.getProperty("os.name");
        AgentsController controller = new AgentsController("localhost", 11211);

        if (osName.contains("Windows")) {
        } else {
            System.out.println("[AGENT_CONTROLLER] Analysis mode, find covered ones!");
            File targetFile = null;
            Map<String, Integer> pointsMap = new HashMap<>();

            try {
                String command = String.format("timeout --signal=9 %s java -noverify -javaagent:%s=mode:throw_e," +
                                "defaultMode:coverage,filter:%s,lineNumber:* -jar %s -o . -s 0 %s 2>&1",
                        timeout, javaagentPath, analysisFilter.replace("$", "\\$"), threadName, torrentFile);
                System.out.println("[AGENT_CONTROLLER] command: " + command);
                process = Runtime.getRuntime().exec(new String[]{"bash", "-c", command}, null, new File(rootPath));

                int input_pid = JMXMonitoringTool.getPidByThreadName(threadName);
                Thread jmxMonitoring = null;

                if (input_pid > 0) {
                    jmxMonitoring = new Thread(() -> {
                        JMXMonitoringTool.MONITORING_SWITCH = true;
                        JMXMonitoringTool.monitorProcessByPid(input_pid, 1000);
                    });
                    jmxMonitoring.start();
                }

                InputStream inputStream = process.getInputStream();
                InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                String line = null;
                while ((line = bufferedReader.readLine()) != null) {
                    if (line.startsWith("INFO PAgent a method which throws an exception executed")) {
                        String key = line.split("key: ")[1];
                        if (pointsMap.containsKey(key)) {
                            pointsMap.put(key, pointsMap.get(key) + 1);
                        } else {
                            pointsMap.put(key, 1);
                        }
                    }  else if (line.contains(endingPattern)) {
                        process.destroy();
                        break;
                    }
                }

                JMXMonitoringTool.MONITORING_SWITCH = false;
                if (jmxMonitoring != null) {
                    jmxMonitoring.join();
                }

                int exitValue = process.waitFor();
                System.out.println("[AGENT_CONTROLLER] process cpu time(in ms): " + JMXMonitoringTool.processCpuTime / 1000000);
                System.out.println("[AGENT_CONTROLLER] average memory usage(in MB): " + JMXMonitoringTool.averageMemoryUsage / 1000000);
                System.out.println("[AGENT_CONTROLLER] peak thread count: " + JMXMonitoringTool.peakThreadCount);

                // make sure the thread is killed
                int pid = JMXMonitoringTool.getPidByThreadName(threadName);
                if (pid > 0) {
                    process = Runtime.getRuntime().exec(new String[]{"bash", "-c", "kill -9 " + pid}, null, new File(rootPath));
                }
                // delete the downloaded file
                targetFile = new File(rootPath + "/" + torrentFile.split("\\.")[0] + ".iso");
                if (targetFile.exists()) {
                    process = Runtime.getRuntime().exec(new String[]{"rm", "-rf", torrentFile.split("\\.")[0] + ".iso"}, null, new File(rootPath));
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            List<String[]> tasksInfo = checkHeaders(controller, rootPath + "/" + targetCsv);
            List<String> task = null;
            for (int i = 1; i < tasksInfo.size(); i++) {
                task = new ArrayList<>(Arrays.asList(tasksInfo.get(i)));
                if (pointsMap.containsKey(task.get(0))) {
                    task.set(10, "yes");
                    task.set(11, pointsMap.get(task.get(0)).toString());
                    tasksInfo.set(i, task.toArray(new String[task.size()]));
                }
            }
            controller.write2csvfile(rootPath + "/" + targetCsv, tasksInfo);
            System.out.println("[AGENT_CONTROLLER] analysis experiment finished");
        }
    }

    public static List checkHeaders(AgentsController controller, String filepath) {
        List<String[]> tasksInfo = controller.readInfoFromFile(filepath);
        List<String> task = new ArrayList<>(Arrays.asList(tasksInfo.get(0)));
        if (task.size() <= 10) {
            // need to add some headers
            task.add("covered"); // index should be 10
            task.add("run times in normal"); // index should be 11
            task.add("run times in injection");
            task.add("injection captured in the business log");
            task.add("downloaded the file");
            task.add("exit status");
            task.add("process cpu time(in seconds)");
            task.add("average memory usage(in MB)");
            task.add("peak thread count");
            tasksInfo.set(0, task.toArray(new String[task.size()]));

            for (int i = 1; i < tasksInfo.size(); i++) {
                task = new ArrayList<>(Arrays.asList(tasksInfo.get(i)));
                for (int j = 0; j < 9; j++) {
                    task.add("-");
                }
                tasksInfo.set(i, task.toArray(new String[task.size()]));
            }

            controller.write2csvfile(filepath, tasksInfo);
            tasksInfo = controller.readInfoFromFile(filepath);
        }

        return tasksInfo;
    }
}
