package bakery.experiment;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import ca.pfv.spmf.algorithms.frequentpatterns.charm.AlgoCharm_Bitset;
import ca.pfv.spmf.input.transaction_database_list_integers.TransactionDatabase;
import ca.pfv.spmf.algorithms.frequentpatterns.charm.AlgoCharm_CompactMatrix;

public class CompareCompactMatrix {

    private static final Path DATA_DIRECTORY = Path.of(
        "D:/KHAI THAC HANH VI KHACH HANG DUA TREN TMPBD/Data"
    );

    public static void main(String[] args) throws Exception {
        // Ví dụ tham số: CHARM 0.01 1
    	if (args.length != 4) {
    	    throw new IllegalArgumentException(
    	        "Nhap: RETAIL hoac MUSHROOM, CHARM_ORIGINAL hoac CHARM_COMPACT, minsup, lan chay."
    	    );
    	}
    	
    	String dataset = args[0].toUpperCase(Locale.ROOT);
    	String algorithm = args[1].toUpperCase(Locale.ROOT);
    	double minsup = Double.parseDouble(args[2]);
    	int runNumber = Integer.parseInt(args[3]);

    	if (!dataset.equals("RETAIL") && !dataset.equals("MUSHROOM")) {
    	    throw new IllegalArgumentException(
    	        "Du lieu phai la RETAIL hoac MUSHROOM."
    	    );
    	}

    	if (!algorithm.equals("CHARM_ORIGINAL")
    	        && !algorithm.equals("CHARM_COMPACT")) {
    	    throw new IllegalArgumentException(
    	        "Ten thuat toan phai la CHARM_ORIGINAL hoac CHARM_COMPACT."
    	    );
    	}

        if (!Double.isFinite(minsup) || minsup <= 0 || minsup > 1) {
            throw new IllegalArgumentException("Minsup phai lon hon 0 va <= 1.");
        }

        if (runNumber < 1) {
            throw new IllegalArgumentException("Lan chay phai tu 1 tro len.");
        }

        String inputName = dataset.equals("RETAIL")
                ? "retail.txt"
                : "mushrooms.txt";

        Path input = DATA_DIRECTORY
                .resolve("BenchmarkDatasets")
                .resolve(inputName);
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Khong tim thay: " + input);
        }

        Path resultDirectory = DATA_DIRECTORY
                .resolve("ExperimentResults")
                .resolve("03_CHARM_CompactMatrix")
                .resolve(dataset)
                .resolve("minsup_" + Double.toString(minsup));
        Files.createDirectories(resultDirectory);

        String label = algorithm + "_" + minsup + "_run" + runNumber;
        Path output = resultDirectory.resolve(label + "_patterns.txt");
        Path statistics = resultDirectory.resolve(label + "_stats.txt");

        // Bộ nhớ được quan sát là heap đang sử dụng của JVM.
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        AtomicLong peakHeap = new AtomicLong(
            memory.getHeapMemoryUsage().getUsed()
        );
        AtomicBoolean sampling = new AtomicBoolean(true);

        // Lấy mẫu khoảng mỗi 5 ms.
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                long used = memory.getHeapMemoryUsage().getUsed();
                peakHeap.accumulateAndGet(used, Math::max);

                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "heap-sampler");

        sampler.setDaemon(true);
        sampler.start();

        long start = System.nanoTime();
        long end;

        try {
            runAlgorithm(algorithm, input, output, minsup);
            end = System.nanoTime();
        } finally {
            peakHeap.accumulateAndGet(
                memory.getHeapMemoryUsage().getUsed(), Math::max
            );
            sampling.set(false);
            sampler.interrupt();
            sampler.join();
        }

        double elapsedMs = (end - start) / 1_000_000.0;
        double peakHeapMiB = peakHeap.get() / (1024.0 * 1024.0);

        // Đếm kết quả sau khi đã kết thúc phép đo thời gian.
        long patternCount;
        try (Stream<String> lines = Files.lines(output)) {
            patternCount = lines
                .filter(line -> line.contains("#SUP:"))
                .count();
        }

        String report = String.format(Locale.ROOT,
            "Algorithm: %s%n"
            + "Minsup: %.4f%n"
            + "Run: %d%n"
            + "Closed itemsets: %d%n"
            + "Elapsed time (ms): %.3f%n"
            + "Sampled peak heap (MiB): %.3f%n",
            algorithm, minsup, runNumber, patternCount,
            elapsedMs, peakHeapMiB
        );
        
        report += "Dataset: " + dataset + System.lineSeparator();
        report += "Input: " + input.toAbsolutePath() + System.lineSeparator();
        report += "SPMF version: 2.67a" + System.lineSeparator();
        report += "Algorithm source: "
                + (algorithm.equals("CHARM_ORIGINAL")
                    ? "SPMF 2.67a original algorithm, unchanged"
                    : "SPMF 2.67a CHARM modified: compact frequent-item matrix")
                + System.lineSeparator();

        report += "CHARM triangular matrix: true"
                + System.lineSeparator();

        report += "Matrix implementation: "
                + (algorithm.equals("CHARM_ORIGINAL")
                    ? "original"
                    : "compact frequent-item matrix")
                + System.lineSeparator();

        report += "JVM arguments: "
                + ManagementFactory.getRuntimeMXBean().getInputArguments()
                + System.lineSeparator();
        report += "Java version: " + System.getProperty("java.version")
                + System.lineSeparator();
        
        Files.writeString(statistics, report);
        System.out.println(report);
        System.out.println("Patterns: " + output);
        System.out.println("Statistics: " + statistics);
    }

    private static void runAlgorithm(
            String algorithm, Path input, Path output, double minsup)
            throws Exception {

        TransactionDatabase database = new TransactionDatabase();
        database.loadFile(input.toString());

        if (algorithm.equals("CHARM_ORIGINAL")) {
            AlgoCharm_Bitset charm = new AlgoCharm_Bitset();

            charm.runAlgorithm(
                output.toString(),
                database,
                minsup,
                true,
                10000
            );

        } else if (algorithm.equals("CHARM_COMPACT")) {
            AlgoCharm_CompactMatrix charm = new AlgoCharm_CompactMatrix();

            charm.runAlgorithm(
                output.toString(),
                database,
                minsup,
                true,
                10000
            );

        } else {
            throw new IllegalArgumentException(
                "Ten thuat toan khong hop le: " + algorithm
            );
        }
    }
}