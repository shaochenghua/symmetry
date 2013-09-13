package org.biojava3.structure.align.symm.census2.analysis;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.biojava.bio.structure.rcsb.RCSBDescription;
import org.biojava.bio.structure.rcsb.RCSBDescriptionFactory;
import org.biojava.bio.structure.rcsb.RCSBPolymer;
import org.biojava.bio.structure.scop.ScopDatabase;
import org.biojava.bio.structure.scop.ScopDescription;
import org.biojava.bio.structure.scop.ScopDomain;
import org.biojava.bio.structure.scop.ScopFactory;
import org.biojava3.structure.align.symm.census2.Result;
import org.biojava3.structure.align.symm.census2.Results;
import org.biojava3.structure.align.symm.census2.Significance;
import org.biojava3.structure.align.symm.census2.SignificanceFactory;
import org.biojava3.structure.align.symm.census2.stats.StatUtils;

/**
 * Classify symmetry by Enzyme Commission number.
 * @author dmyerstu
 */
public class ECCorrelation {

	private static final Logger logger = LogManager.getLogger(ECCorrelation.class.getName());

	private Map<String,String> ecsBySymmDomain = new HashMap<String,String>();
	private Map<String,String> ecsByAsymmDomain = new HashMap<String,String>();

	public ECCorrelation(Results census) {

		ScopDatabase scop = ScopFactory.getSCOP(ScopFactory.VERSION_1_75A);
		Significance sig = SignificanceFactory.rotationallySymmetricSmart();

		int i = 0;
		for (Result result : census.getData()) {

			try {

				String scopId = result.getScopId();
				ScopDomain domain = scop.getDomainByScopID(scopId);
				if (domain == null) {
					logger.error(result.getScopId() + " is null");
					continue;
				}

				// got a result; what's its EC?

				// we need to find the correct polymers corresponding to the domain
				// note that this still isn't perfect, since we don't know what part of the polymer actually does the function
				List<RCSBPolymer> polymers = new ArrayList<RCSBPolymer>();
				Set<String> chains = domain.getChains();
				RCSBDescription desc = RCSBDescriptionFactory.get(domain.getPdbId());
				for (RCSBPolymer polymer : desc.getPolymers()) {
					for (Character chain : polymer.getChains()) {
						if (chains.contains(String.valueOf(chain))) {
							polymers.add(polymer);
							break;
						}
					}
				}

				// get the EC numbers
				// use a set because we don't want > 1 just because we have duplicates
				NavigableSet<String> ecs = new TreeSet<String>();
				for (RCSBPolymer polymer : polymers) {
					String ec = polymer.getEnzClass();
					if (ec != null) ecs.add(ec);
				}

				if (ecs.size() == 1) {

					String ec = ecs.first();

					if (sig.isSignificant(result)) {
						ecsBySymmDomain.put(scopId, ec);
					} else {
						ecsByAsymmDomain.put(scopId, ec);
					}

				} else if (ecs.size() > 1) {
					logger.info("Found different EC numbers for " + domain.getScopId()); // technically, this doesn't mean anything's wrong
				}

				if (i > 0 && i % 1000 == 0) logger.debug("Working on #" + i);

			} catch (RuntimeException e) {
				e.printStackTrace();
				logger.error(e);
			} finally {
				i++;
			}

		}
	}

	/**
	 * 
	 * @param ec
	 * @param level
	 * @return
	 */
	private String getLabel(String ec, int level) {
		String[] parts = ec.split("\\.");
		String label = parts[0];
		for (int i = 1; i <= level; i++) {
			// this can happen if the EC number isn't fully specified (in fact, this is common)
			if (i >= parts.length) return null;
			label += "." + parts[i];
		}
		return label;
	}

	/**
	 * Prints a comparison between symmetric and asymmetric results for each EC.
	 * @param level The depth of the EC: 0 for top-level and 3 for 3rd-tier
	 * @param maxExamples The maximum number of example folds to list; example folds are sorted from most prevalent to least
	 */
	public void printComparison(int level, int maxExamples) {

		ScopDatabase scop = ScopFactory.getSCOP(ScopFactory.VERSION_1_75A);

		if (level < 0 || level > 3) throw new IllegalArgumentException("Level must be between 0 and 3, inclusive");

		/*
		 * build a map of the number of symmetric domains by EC
		 * also record which folds each EC includes, and the number of times that fold is used
		 */
		Set<String> labels = new LinkedHashSet<String>(); // these are the parts of the ECs we care about
		Map<String,Integer> nSymmDomainsByEc = new HashMap<String,Integer>();
		final Map<String,Map<String,Integer>> symmFoldsByEcs = new HashMap<String,Map<String,Integer>>();
		for (Map.Entry<String,String> entry : ecsBySymmDomain.entrySet()) {

			final String scopId = entry.getKey();
			final String ec = entry.getValue();

			String label = getLabel(ec, level);
			if (label == null) continue;

			// record the fold
			if (!symmFoldsByEcs.containsKey(label)) symmFoldsByEcs.put(label, new HashMap<String,Integer>());
			ScopDomain domain = scop.getDomainByScopID(scopId);
			ScopDescription desc = scop.getScopDescriptionBySunid(domain.getFoldId());
			String fold = desc.getName();
			StatUtils.plus(symmFoldsByEcs.get(label), fold);

			StatUtils.plus(nSymmDomainsByEc, label);
			labels.add(label);
		}

		/*
		 * build a map of the number of asymmetric domains by EC
		 * in this case we don't care about the folds
		 */
		Map<String,Integer> nAsymmDomainsByEc = new HashMap<String,Integer>();
		for (String ec : ecsByAsymmDomain.values()) {
			String label = getLabel(ec, level);
			if (label == null) continue;
			StatUtils.plus(nAsymmDomainsByEc, label);
			labels.add(label);
		}

		/*
		 * now print the results
		 */
		for (String label : labels) {

			// print basic stats: % symm domains and number of domains
			double fractionSymm = 0, fractionAsymm = 0;
			if (nSymmDomainsByEc.containsKey(label)) fractionSymm = (double) nSymmDomainsByEc.get(label);
			if (nAsymmDomainsByEc.containsKey(label)) fractionAsymm = (double) nAsymmDomainsByEc.get(label);
			System.out.print(label + "\t" + StatUtils.formatP(fractionSymm / (fractionSymm+fractionAsymm)) + "\t" + (fractionSymm+fractionAsymm));

			/*
			 * now we want to list example domains
			 * for this, we want the top most common folds
			 * so we need a new map sorted by values
			 */
			if (fractionSymm > 0) {
				final Map<String,Integer> domainCountByFold = symmFoldsByEcs.get(label);
				System.out.print("\t" + domainCountByFold.size()); // print out the number of folds

				if (domainCountByFold != null) {
					Comparator<String> nDomainsInFoldComp = new Comparator<String>() {
						@Override
						public int compare(String o1, String o2) {
							if (!domainCountByFold.containsKey(o1) || !domainCountByFold.containsKey(o2)) return 0;
							return domainCountByFold.get(o2).compareTo(domainCountByFold.get(o1));
						}
					};
					SortedMap<String,Integer> sortedFoldExamples = new TreeMap<String,Integer>(nDomainsInFoldComp);
					sortedFoldExamples.putAll(domainCountByFold);

					// now we have some examples, so we can print them out
					int i = 0;
					for (Map.Entry<String,Integer> entry : sortedFoldExamples.entrySet()) {
						System.out.print("\t" + entry.getKey());
						i++;
						if (i > maxExamples) break;
					}

				}
			}
			System.out.println(); // we're done with this EC
		}
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String,String> entry : ecsBySymmDomain.entrySet()) {
			sb.append(entry.getKey() + "\t" + entry.getValue() + StatUtils.NEWLINE);
		}
		sb.append("---------------------------------------------------" + StatUtils.NEWLINE);
		for (Map.Entry<String,String> entry : ecsByAsymmDomain.entrySet()) {
			sb.append(entry.getKey() + "\t" + entry.getValue() + StatUtils.NEWLINE);
		}
		return sb.toString();
	}

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		if (args.length < 1 || args.length > 2) {
			System.err.println("Usage: " + ECCorrelation.class.getSimpleName() + " census-file.xml [ecs-output-file.tsv]");
			return;
		}
		ECCorrelation ecs = new ECCorrelation(Results.fromXML(new File(args[0])));
		System.out.println("============List of EC numbers of domains============");
		System.out.println(ecs.toString());
		if (args.length > 1) {
			PrintWriter pw = null;
			try {
				pw = new PrintWriter(new File(args[1]), "UTF-8");
				pw.print(ecs.toString());
			} catch (IOException e) {
				logger.error("Couldn't print to " + args[1]);
			} finally {
				if (pw != null) pw.close();
			}
		}
		System.out.println("=====================================================" + StatUtils.NEWLINE);
		System.out.println("===================EC numbers level 0================");
		ecs.printComparison(0, 10);
		System.out.println("=====================================================" + StatUtils.NEWLINE);
		System.out.println("===================EC numbers level 1================");
		ecs.printComparison(1, 10);
		System.out.println("=====================================================" + StatUtils.NEWLINE);
	}

}
