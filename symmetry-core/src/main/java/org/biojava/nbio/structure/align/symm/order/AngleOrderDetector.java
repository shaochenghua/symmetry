package org.biojava.nbio.structure.align.symm.order;

import org.biojava.nbio.structure.Atom;
import org.biojava.nbio.structure.align.model.AFPChain;
import org.biojava.nbio.structure.align.util.RotationAxis;
import org.biojava.nbio.structure.symmetry.internal.OrderDetector;
import org.biojava.nbio.structure.symmetry.internal.RefinerFailedException;

/**
 * Guesses an order of rotational symmetry from the angle. The second method
 * described in Myers-Turnbull (2014).
 * 
 * Searches for an order k such that the angle observed closely matches 2pi/k.
 * This misses many valid rotations, and is only retained for reproducing paper
 * results.
 * @deprecated Use {@link AngleOrderDetector3} instead
 * @author dmyersturnbull
 */
@Deprecated
public class AngleOrderDetector implements OrderDetector {

	private int maxOrder = 8;
	private final double angleError;

	/**
	 * @param angleError maximum angular error, in radians
	 */
	public AngleOrderDetector(double angleError) {
		super();
		this.angleError = angleError;
	}

	/**
	 * 
	 * @param maxOrder maximum order to consider
	 * @param angleError maximum angular error, in radians
	 */
	public AngleOrderDetector(int maxOrder, double angleError) {
		super();
		this.maxOrder = maxOrder;
		this.angleError = angleError;
	}

	@Override
	public int calculateOrder(AFPChain afpChain, Atom[] ca) throws RefinerFailedException {
		
		try {
			RotationAxis axis = new RotationAxis(afpChain);
			double theta = axis.getAngle();

			double bestDelta = angleError;
			int bestOrder = 1;
			for (int order = 2; order < maxOrder; order++) {
				double delta = Math.abs(2 * Math.PI / order - theta);
				if (delta < bestDelta) {
					bestOrder = order;
					bestDelta = delta;
				}
			}
			return bestOrder;
		} catch (Exception e) {
			throw new RefinerFailedException(e);
		}
	}

	@Override
	public String toString() {
		return getClass().getSimpleName()+"[angleError="+angleError+",maxOrder="+maxOrder+"]";
	}
}
