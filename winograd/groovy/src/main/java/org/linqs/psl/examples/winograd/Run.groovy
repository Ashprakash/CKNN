 	package org.linqs.psl.examples.winograd;

import org.linqs.psl.application.inference.InferenceApplication;
import org.linqs.psl.application.inference.MPEInference;
import org.linqs.psl.config.Config;
import org.linqs.psl.database.Database;
import org.linqs.psl.database.DataStore;
import org.linqs.psl.database.Partition;
import org.linqs.psl.database.loading.Inserter;
import org.linqs.psl.database.rdbms.driver.H2DatabaseDriver;
import org.linqs.psl.database.rdbms.driver.H2DatabaseDriver.Type;
import org.linqs.psl.database.rdbms.driver.PostgreSQLDriver;
import org.linqs.psl.database.rdbms.RDBMSDataStore;
import org.linqs.psl.evaluation.statistics.DiscreteEvaluator;
import org.linqs.psl.evaluation.statistics.Evaluator;
import org.linqs.psl.groovy.PSLModel;
import org.linqs.psl.model.atom.GroundAtom;
import org.linqs.psl.model.term.Constant;
import org.linqs.psl.model.term.ConstantType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;

/**
 * A simple example.
 * In this example, we try to determine if two people know each other.
 * The model uses two features: where the people lived and what they like.
 * The model also has options to include symmetry and transitivity rules.
 */
public class Run {
	private static final String PARTITION_OBSERVATIONS = "observations";
	private static final String PARTITION_TARGETS = "targets";
	private static final String PARTITION_TRUTH = "truth";

	private static final String DATA_PATH = Paths.get("..", "data").toString();
	private static final String OUTPUT_PATH = "inferred-predicates";

	private static Logger log = LoggerFactory.getLogger(Run.class)

	private DataStore dataStore;
	private PSLModel model;

	public Run() {
		String suffix = System.getProperty("user.name") + "@" + getHostname();
		String baseDBPath = Config.getString("dbpath", System.getProperty("java.io.tmpdir"));
		String dbPath = Paths.get(baseDBPath, this.getClass().getName() + "_" + suffix).toString();
		dataStore = new RDBMSDataStore(new H2DatabaseDriver(Type.Disk, dbPath, true));
		// dataStore = new RDBMSDataStore(new PostgreSQLDriver("psl", true));

		model = new PSLModel(this, dataStore);
	}

	/**
	 * Defines the logical predicates used in this model
	 */
	private void definePredicates() {
		model.add predicate: "Lived", types: [ConstantType.UniqueStringID, ConstantType.UniqueStringID];
		model.add predicate: "Likes", types: [ConstantType.UniqueStringID, ConstantType.UniqueStringID];
		model.add predicate: "Knows", types: [ConstantType.UniqueStringID, ConstantType.UniqueStringID];
    	model.add predicate: "Context", types: [ConstantType.UniqueStringID, ConstantType.UniqueStringID];
	}

	/**
	 * Defines the rules for this model.
	 */
	private void defineRules() {
		log.info("Defining model rules");

		model.add(
			rule: "20: Lived(P1, L) & Lived(P2, L) & (P1 != P2) -> Knows(P1, P2) ^2"
		);

		model.add(
			rule: "5: Lived(P1, L1) & Lived(P2, L2) & (P1 != P2) & (L1 != L2) -> !Knows(P1, P2) ^2"
		);

		model.add(
			rule: "10: Likes(P1, L) & Likes(P2, L) & (P1 != P2) -> Knows(P1, P2) ^2"
		);

		model.add(
			rule: "5: Knows(P1, P2) & Knows(P2, P3) & (P1 != P3) -> Knows(P1, P3) ^2"
		);

		model.add(
			rule: "Knows(P1, P2) = Knows(P2, P1) ."
		);

		model.add(
			rule: "5: !Knows(P1, P2) ^2"
		);

		log.debug("model: {}", model);
	}

	/**
	 * Load data from text files into the DataStore.
	 * Three partitions are defined and populated: observations, targets, and truth.
	 * Observations contains evidence that we treat as background knowledge and use to condition our inferences.
	 * Targets contains the inference targets - the unknown variables we wish to infer.
	 * Truth contains the true values of the inference variables and will be used to evaluate the model's performance.
	 */
	private void loadData(Partition obsPartition, Partition targetsPartition, Partition truthPartition) {
    log.info("Are you working");
		log.info("Loading data into database");

		Inserter inserter = dataStore.getInserter(Lived, obsPartition);
		inserter.loadDelimitedData(Paths.get(DATA_PATH, "lived_obs.txt").toString());

		inserter = dataStore.getInserter(Likes, obsPartition);
		inserter.loadDelimitedDataTruth(Paths.get(DATA_PATH, "likes_obs.txt").toString());

		inserter = dataStore.getInserter(Knows, obsPartition);
		inserter.loadDelimitedData(Paths.get(DATA_PATH, "knows_obs.txt").toString());

		inserter = dataStore.getInserter(Knows, targetsPartition);
		inserter.loadDelimitedData(Paths.get(DATA_PATH, "knows_targets.txt").toString());

		inserter = dataStore.getInserter(Knows, truthPartition);
		inserter.loadDelimitedDataTruth(Paths.get(DATA_PATH, "knows_truth.txt").toString());

	    inserter = dataStore.getInserter(Context, truthPartition);
			inserter.loadDelimitedData(Paths.get(DATA_PATH, "context_obs.txt").toString());
	}

	/**
	 * Run inference to infer the unknown Knows relationships between people.
	 */
	private void runInference(Partition obsPartition, Partition targetsPartition) {
		log.info("Starting inference");

		Database inferDB = dataStore.getDatabase(targetsPartition, [Lived, Likes, Context] as Set, obsPartition);

		InferenceApplication inference = new MPEInference(model, inferDB);
		inference.inference();

		inference.close();
		inferDB.close();

		log.info("Inference complete");
	}

	/**
	 * Writes the output of the model into a file
	 */
	private void writeOutput(Partition targetsPartition) {
		Database resultsDB = dataStore.getDatabase(targetsPartition);

		(new File(OUTPUT_PATH)).mkdirs();
		FileWriter writer = new FileWriter(Paths.get(OUTPUT_PATH, "KNOWS.txt").toString());

		for (GroundAtom atom : resultsDB.getAllGroundAtoms(Knows)) {
			for (Constant argument : atom.getArguments()) {
				writer.write(argument.toString() + "\t");
			}
			writer.write("" + atom.getValue() + "\n");
		}

		writer.close();
		resultsDB.close();
	}

	/**
	 * Run statistical evaluation scripts to determine the quality of the inferences
	 * relative to the defined truth.
	 */
	private void evalResults(Partition targetsPartition, Partition truthPartition) {
		Database resultsDB = dataStore.getDatabase(targetsPartition, [Lived, Likes, Context] as Set);
		Database truthDB = dataStore.getDatabase(truthPartition, [Knows] as Set);

		Evaluator eval = new DiscreteEvaluator();
		eval.compute(resultsDB, truthDB, Knows);
		log.info(eval.getAllStats());

		resultsDB.close();
		truthDB.close();
	}

	public void run() {
		Partition obsPartition = dataStore.getPartition(PARTITION_OBSERVATIONS);
		Partition targetsPartition = dataStore.getPartition(PARTITION_TARGETS);
		Partition truthPartition = dataStore.getPartition(PARTITION_TRUTH);

		definePredicates();
		defineRules();
		loadData(obsPartition, targetsPartition, truthPartition);
		runInference(obsPartition, targetsPartition);
		writeOutput(targetsPartition);
		evalResults(targetsPartition, truthPartition);

		dataStore.close();
	}

	/**
	 * Run this model from the command line
	 * @param args - the command line arguments
	 */
	public static void main(String[] args) {
		Run run = new Run();
		run.run();
	}

	private static String getHostname() {
		String hostname = "unknown";

		try {
			hostname = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException ex) {
			log.warn("Hostname can not be resolved, using '" + hostname + "'.");
		}

		return hostname;
	}
}
