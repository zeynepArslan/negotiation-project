package negotiator.group7;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import negotiator.Bid;
import negotiator.bidding.BidDetails;
import negotiator.boaframework.NegotiationSession;
import negotiator.boaframework.OpponentModel;
import negotiator.issue.Issue;
import negotiator.issue.IssueDiscrete;
import negotiator.issue.Objective;
import negotiator.issue.ValueDiscrete;
import negotiator.utility.Evaluator;
import negotiator.utility.EvaluatorDiscrete;
import negotiator.utility.UtilitySpace;

/**
 * Opponent Model using Adaptive Frequency Modeling. The learn value addition is adjusted to how wrong
 * the current utility is expected to be.
 * @author Bas Dado (b.dado@student.tudelft.nl)
 *
 */
public class Group7_FrequencyOM extends OpponentModel {


	/** 
	* The learning coefficient is the weight that is added each turn to the issue weights
	* which changed. It's a trade-off between concession speed and accuracy.
	*/
	private double learnCoef;
	
	/**
	 * value which is added to a value if it is found. Determines how fast the value weights (within issues) converge.
	 */
	private int learnValueAddition;
	/**
	 * The number of issues in the negotiation
	 */
	private int amountOfIssues;
	
	/**
	 * The mean number of bids we assume the opponent skips during the time it's conceding
	 */
	private final double meanBidSkip = 1.5;
	/**
	 * The maximum difference we allow the estimated utility to have from the expected utility before we start adapting the learning rate.
	 * The right margin means the maximum difference lower than the expected utility (e.g. if the expected utility is 0.90 and the rightMargin is 0.05,
	 * then we use an adaptive learning rate if the cur utility < 0.90 - 0.05 = 0.85
	 */
	private final double rightMargin = 0.03;
	/**
	 * The maximum difference we allow the estimated utility to have from the expected utility before we start adapting the learning rate.
	 * The left margin means the maximum difference higher than the expected utility (e.g. if the expected utility is 0.90 and the leftMargin is 0.05,
	 * then we use an adaptive learning rate if the cur utility > 0.90 + 0.05 = 0.95
	 */
	private final double leftMargin = 0.05;
	
	/**
	 * The mean value of the normal distribution we assume the utility space has
	 */
	private final double mu = 0.6;
	/**
	 * The variance of the normal distribution we assume the utility space has
	 */
	private final double sigma = 0.019;
	
	/**
	 * The maximum learn rate that adaptive modeling can use.
	 */
	private final int maxLearnValueAddition = 100;
	
	/**
	 * List containing all expected utilities we have estimated each time a new bid arrived.
	 * Used to check the quality of the model if bid is repeated 
	 */
	private ArrayList<BidMap> expectedUtils;
	
	/**
	 * The number of distinct bids required before we assume the model is reliable
	 */
	private int opponentModelReliableThreshold;
	
	private Helper ourHelper;
	
	/**
	 * Initializes the utility space of the opponent such that all value
	 * issue weights are equal.
	 */
	@Override
	public void init(NegotiationSession negotiationSession, HashMap<String, Double> parameters) throws Exception {
		super.init(negotiationSession, parameters);
		this.negotiationSession = negotiationSession;
		if (parameters != null && parameters.get("l") != null) {
			learnCoef = parameters.get("l");
		} else {
			learnCoef = 0.3;
		}
		learnValueAddition = 1;
		initializeModel();
		ourHelper = Helper.get(negotiationSession);
		ourHelper.setOpponentModel(this);
		ourHelper.setSession(negotiationSession);
		
		opponentModelReliableThreshold = (int)Math.round((double)opponentUtilitySpace.getDomain().getNumberOfPossibleBids() * 0.025);
		opponentModelReliableThreshold = Math.max(opponentModelReliableThreshold, 5);
		
		expectedUtils = new ArrayList<BidMap>();
	}
	
	private void initializeModel(){
		opponentUtilitySpace = new UtilitySpace(negotiationSession.getUtilitySpace());
		amountOfIssues = opponentUtilitySpace.getDomain().getIssues().size();
		double commonWeight = 1D / (double)amountOfIssues;    
		
		// initialize the weights
		for(Entry<Objective, Evaluator> e: opponentUtilitySpace.getEvaluators()){
			// set the issue weights
			opponentUtilitySpace.unlock(e.getKey());
			e.getValue().setWeight(commonWeight);
			try {
				// set all value weights to one (they are normalized when calculating the utility)
				for(ValueDiscrete vd : ((IssueDiscrete)e.getKey()).getValues())
					((EvaluatorDiscrete)e.getValue()).setEvaluation(vd,1);  
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}
	
	/**
	 * Determines the difference between bids. For each issue, it is determined if the
	 * value changed. If this is the case, a 1 is stored in a hashmap for that issue, else a 0.
	 * 
	 * @param a bid of the opponent
	 * @param another bid
	 * @return
	 */
	private HashMap<Integer, Integer> determineDifference(BidDetails first, BidDetails second){
		
		HashMap<Integer, Integer> diff = new HashMap<Integer, Integer>();
		try{
			for(Issue i : opponentUtilitySpace.getDomain().getIssues()){
				diff.put(i.getNumber(), (
						((ValueDiscrete)first.getBid().getValue(i.getNumber())).equals((ValueDiscrete)second.getBid().getValue(i.getNumber()))
						 				)?0:1);
			}
		} catch (Exception ex){
			ex.printStackTrace();
		}
		
		return diff;
	}
	
	/**
	 * Updates the opponent model given a bid.
	 */
	@Override
	public void updateModel(Bid opponentBid, double time) {		
		if(negotiationSession.getOpponentBidHistory().size() < 2) {
			return;
		}
		int numberOfUnchanged = 0;
		BidDetails oppBid = negotiationSession.getOpponentBidHistory().getLastBidDetails();
		BidDetails prevOppBid = negotiationSession.getOpponentBidHistory().getHistory().get(negotiationSession.getOpponentBidHistory().size() - 2);
		HashMap<Integer, Integer> lastDiffSet = determineDifference(prevOppBid, oppBid);
		
		// count the number of changes in value
		for(Integer i: lastDiffSet.keySet()){
			if(lastDiffSet.get(i) == 0)
				numberOfUnchanged ++;
		}
		
		// This is the value to be added to weights of unchanged issues before normalization. 
		// Also the value that is taken as the minimum possible weight, (therefore defining the maximum possible also). 
		double goldenValue = learnCoef / (double)amountOfIssues;
		// The total sum of weights before normalization.
		double totalSum = 1D + goldenValue * (double)numberOfUnchanged;
		// The maximum possible weight
		double maximumWeight = 1D - learnCoef / totalSum; 
		
		
		
		// re-weighing issues while making sure that the sum remains 1 
		for(Integer i: lastDiffSet.keySet()){
			if (lastDiffSet.get(i) == 0 && opponentUtilitySpace.getWeight(i)< maximumWeight)
				opponentUtilitySpace.setWeight(opponentUtilitySpace.getDomain().getObjective(i), (opponentUtilitySpace.getWeight(i) + goldenValue)/totalSum);
			else
				opponentUtilitySpace.setWeight(opponentUtilitySpace.getDomain().getObjective(i), opponentUtilitySpace.getWeight(i)/totalSum);
		}
		
		// Update the value weights
		updateValueWeights(oppBid);
	}

	/**
	 * Uses the adaptive frequency modeling algorithm to update the values weights within each issue 
	 * based on the last bid
	 * @param oppBid The most recent bid done by the opponent
	 */
	private void updateValueWeights(BidDetails oppBid) {
		List<Bid> distinctBids = ourHelper.getDistinctBids(negotiationSession.getOpponentBidHistory());
		try {
			double curUtil = this.getBidEvaluation(oppBid.getBid());
			
			BidMap match = null;
			for (BidMap bm: expectedUtils) {
				if (bm.bid.equals(oppBid.getBid()))
					match = bm;
			}
			if (match == null) {
				match = new BidMap(oppBid.getBid(), ExpectedNewBidUtil());
				expectedUtils.add(match);
			}
			double expectedUtil = match.util;
			
			int actualLearnRate = learnValueAddition;
			if (curUtil < expectedUtil - rightMargin || curUtil > expectedUtil + leftMargin) { // Check if we're outside the boundaries
				// Algebra to find the new learnValueAddition (where w_i is the weight of issue i, and v_i,j is the value item j from issue i:
				// U(offer) = sum_i(w_i (v_{i,j}/sum_j(v_{i,j}))
				// Frequency modeling will add the following:
				// U(offer) = sum_i(w_i (v_{i,j} + x)/(sum_j(v_{i,j}) + x))
				// However this is a bit hard to solve algebraically, so we just try some values for x and choose the best one.
				double closestUtil = 0;
				double utilToApproach = expectedUtil;
					
				// Estimate the utility for all possible learn rates
				for (int i = 1; i <= maxLearnValueAddition; i++) {
					double estimatedUtil = calculateUtilityUsingLearnRate(oppBid, i);
					if (Math.abs(estimatedUtil - utilToApproach) < Math.abs(closestUtil - utilToApproach)) {
						closestUtil = estimatedUtil;
						actualLearnRate = i;
					}
				}
			}
			
			// Then update the values using the optimal learn found above
			UpdateValues(oppBid, actualLearnRate);
			
			Log.dln(", expectedUtil: " + Log.format(expectedUtil, "0.000") + ", estimatedUtil: " + Log.format(curUtil, "0.000") + ", New estimated util: " + Log.format(this.getBidEvaluation(oppBid.getBid()), "0.000") + ", ActualLearnRate: " + actualLearnRate );
		} catch(Exception ex){
			ex.printStackTrace();
		}
		
		// If we had a certain number of original (distinct) bids, then we assume the opponent model is now reliable.
		boolean wasReliable = ourHelper.isOpponentModelReliable();
		ourHelper.setOpponentModelReliable(distinctBids.size() > opponentModelReliableThreshold);
		if (!wasReliable && ourHelper.isOpponentModelReliable())
			Log.newLine("The opponent model is now assumed to be reliable");
	}
	
	/**
	 * Updates the value weights (within each issue) using the given learnRate and opponent bid. This method 
	 * is the most important method for the frequency model value weights.
	 * @param oppBid The bid for which the values need to be adjusted
	 * @param learnRate The learn rate to use for adjusting the weights.
	 * @throws Exception
	 */
	private void UpdateValues(BidDetails oppBid, int learnRate) throws Exception
	{
		// Then for each issue value that has been offered last time, a constant value is added to its corresponding ValueDiscrete.
		for(Entry<Objective, Evaluator> e: opponentUtilitySpace.getEvaluators()){
			( (EvaluatorDiscrete)e.getValue() ).setEvaluation(oppBid.getBid().getValue(((IssueDiscrete)e.getKey()).getNumber()), 
				( learnRate + 
					((EvaluatorDiscrete)e.getValue()).getEvaluationNotNormalized( 
						( (ValueDiscrete)oppBid.getBid().getValue(((IssueDiscrete)e.getKey()).getNumber()) ) 
					)
				)
			);
		}
	}
	
	/**
	 * Calculates the utility a certain bid would get if we applied frequency modeling with the given learn rate. Issue weights remain unchanged
	 * @param oppBid The bid for which the utility should be calculated
	 * @param learnRate The learn rate to use for calculating the utility
	 * @return The expected utility of the given bid if frequency modeling with the given learn-rate is used.
	 * @throws Exception Throws exception thrown by the BOA framework function on oppBid.getBid().getValue()
	 */
	private double calculateUtilityUsingLearnRate(BidDetails oppBid, int learnRate) throws Exception {
		double utility = 0;
		for (Entry<Objective, Evaluator> e: opponentUtilitySpace.getEvaluators()) { // Iterates over all issues
			EvaluatorDiscrete e2 = ((EvaluatorDiscrete)e.getValue());
			double issueWeight = e2.getWeight();
			int itemValue = e2.getValue((ValueDiscrete)oppBid.getBid().getValue(e.getKey().getNumber()));
			itemValue += learnRate;
			double normalizedItemValue = (double)itemValue / Math.max(e2.getValue((ValueDiscrete) e2.getMaxValue()), itemValue + learnRate);
			utility += issueWeight * normalizedItemValue;
			//Log.dln("issueWeight: " + issueWeight + ", normalizedItemValue: " + normalizedItemValue + ", valueSum: " + valueSum + "itemValue: " + itemValue);
		}
		return utility;
	}
	
	/**
	 * Finds the minimum util we currently expect from the opponent (e.g. what strategy we expect)
	 * @return The expected utility of a new bid if the opponent makes one
	 */
	public double ExpectedNewBidUtil()
	{
		// The expected minimum utility is a function of the number of different offers we have received and the number 
		// of different offers possible. Note that we assume the opponents utility space is sort of uniformly distributed
		List<Bid> distinctBids = ourHelper.getDistinctBids(negotiationSession.getOpponentBidHistory());
		double distinctBidCount = (double)distinctBids.size() * meanBidSkip;
		//double meanConcessionPerNewBid = 1D / ((double)opponentUtilitySpace.getDomain().getNumberOfPossibleBids());
		//return 1D - (((double)distinctBids.size()) * meanBidSkip * meanConcessionPerNewBid);
		double percentageOfBidsSeen = 1D - (distinctBidCount * meanBidSkip / ((double)opponentUtilitySpace.getDomain().getNumberOfPossibleBids()));
		return Math.min(normInverseCdf(percentageOfBidsSeen, mu, sigma), 1D);
	}
	
	/**
	 * Calculates the quantile function at a given probability for a normal distribution.
	 * @param p The probability for which the quantile function needs to be evaluated
	 * @param mu The mean of the normal distribution
	 * @param sigmaSquared The variance of the normal distribution
	 * @return The location of the quantile
	 */
	private static double normInverseCdf(double p, double mu, double sigmaSquared) {
		double ie  = inverseErf(2D * p - 1D);
		//Log.dln("p = " + p + ", inverseErf=" + ie + ", x = " + (2D * p - 1D));
		return mu + Math.sqrt(sigmaSquared) * Math.sqrt(2D) * ie;
	}
	
	/**
	 * Calculates the approximate inverse error function (Erf^-1 (x))
	 * @param x The value for which we want to know the erf
	 * @return The inverse error function value
	 */
	private static double inverseErf(double x) {
		double sgn = x < 0 ? -1 : (x == 0 ? 0 : 1);
		double a = 0.147;
		double pia = 2D / (Math.PI * a);
		double logpart = Math.log(1D - Math.pow(x, 2D));
		return sgn * Math.sqrt(
				Math.sqrt( Math.pow(pia + logpart/2D, 2D) - (logpart/ a) ) - 
				(pia + (logpart/2D))
				);
	}
	
	@Override
	public double getBidEvaluation(Bid bid) {
		double result = 0;
		try {
			result = opponentUtilitySpace.getUtility(bid);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}
	
	@Override
	public String getName() {
		return "Group7 Adaptive Frequency Model";
	}
	
	/**
	 * Class used as a wrapper to save both a bid and the estimated util used for that bid
	 * @author Bas Dado
	 *
	 */
	private class BidMap {
		public Bid bid;
		public Double util;
		
		public BidMap(Bid bid, Double util) {
			this.bid = bid;
			this.util = util;
		}
	}
}
