package fiji.plugin.nperry.features;

import java.util.Arrays;

import mpicbg.imglib.container.array.ArrayContainerFactory;
import mpicbg.imglib.cursor.special.SphereCursor;
import mpicbg.imglib.image.Image;
import mpicbg.imglib.image.ImageFactory;
import mpicbg.imglib.image.display.imagej.ImageJFunctions;
import mpicbg.imglib.type.numeric.RealType;
import mpicbg.imglib.type.numeric.integer.UnsignedByteType;
import Jama.EigenvalueDecomposition;
import Jama.Matrix;
import fiji.plugin.nperry.Feature;
import fiji.plugin.nperry.Spot;

public class BlobMorphology <T extends RealType<T>> extends IndependentFeatureAnalyzer {

	/*
	 * Fields
	 */
	
	private final static Feature[] featurelist_sa 	= {Feature.ELLIPSOIDFIT_SEMIAXISLENGTH_A, 	Feature.ELLIPSOIDFIT_SEMIAXISLENGTH_B, 	Feature.ELLIPSOIDFIT_SEMIAXISLENGTH_C};
	private final static Feature[] featurelist_phi 	= {Feature.ELLIPSOIDFIT_AXISPHI_A, 			Feature.ELLIPSOIDFIT_AXISPHI_B, 		Feature.ELLIPSOIDFIT_AXISPHI_C };
	private final static Feature[] featurelist_theta = {Feature.ELLIPSOIDFIT_AXISTHETA_A, 		Feature.ELLIPSOIDFIT_AXISTHETA_B, 		Feature.ELLIPSOIDFIT_AXISTHETA_C}; 
	
	private static final double SIGNIFICANCE_FACTOR = 1.5;
	private static final Feature FEATURE = Feature.MORPHOLOGY;
	private static final int ELLIPSOID = 1;
	private static final int SPHERICAL = 0;
	private Image<T> img;
	private float diam;
	private float[] calibration;
	
	/*
	 * CONSTRUCTORS
	 */
	
	public BlobMorphology (Image<T> img, float diam, float[] calibration) {
		this.img = img;
		this.diam = diam;
		this.calibration = calibration;
	}
	
	public BlobMorphology (Image<T> img, float diam) {
		this(img, diam, img.getCalibration());
	}
	
	public BlobMorphology (Image<T> img, double diam, double[] calibration) {
		
		// 1 - Convert double[] to float[]
		float[] fCalibration = new float[3];
		for (int i = 0; i < calibration.length; i++) {
			fCalibration[i] = (float) calibration[i];
		}
		
		// 2 - Construct
		this.img = img;
		this.diam = (float) diam;
		this.calibration = fCalibration;
	}
	
	public BlobMorphology (Image<T> img, double diam) {
		this(img, (float) diam, img.getCalibration());
	}
	
	/*
	 * PUBLIC METHODS
	 */
	
	@Override
	public Feature getFeature() {
		return FEATURE;
	}
	
	@Override
	public void process(Spot spot) {
		final SphereCursor<T> cursor = new SphereCursor<T>(img, spot.getCoordinates(), diam/2);
		double x, y, z;
		double x2, y2, z2;
		double mass, totalmass = 0;
		double Ixx = 0, Iyy = 0, Izz = 0, Ixy = 0, Ixz = 0, Iyz = 0;
		int[] position = cursor.createPositionArray();

		while (cursor.hasNext()) {
			cursor.fwd();
			mass = cursor.getType().getRealDouble();
			cursor.getRelativePosition(position);
			x = position[0] / calibration[0];
			y = position[1] / calibration[1];
			z = position[2] / calibration[2];
			totalmass += mass;
			x2 = x*x;
			y2 = y*y;
			z2 = z*z;
			Ixx += mass*(y2+z2);
			Iyy += mass*(x2+z2);
			Izz += mass*(x2+y2);
			Ixy -= mass*x*y;
			Ixz -= mass*x*z;
			Iyz -= mass*y*z;
		}
		cursor.close();
		
		Matrix mat = new Matrix( new double[][] { 
				{ Ixx, Ixy, Ixz },
				{ Ixy, Iyy, Iyz },
				{ Ixz, Iyz, Izz } } );
		mat.timesEquals(1/totalmass);
		EigenvalueDecomposition eigdec = mat.eig();
		double[] eigenvalues = eigdec.getRealEigenvalues();
		double[] eigenvalues_copy = eigenvalues.clone();
		Matrix eigenvectors = eigdec.getV();
		
		Arrays.sort(eigenvalues_copy); // TODO use this for classification
		double I1 = eigenvalues[0];
		double I2 = eigenvalues[1];
		double I3 = eigenvalues[2];
		double a = Math.sqrt( 2.5 *(I2+I3-I1) );
		double b = Math.sqrt( 2.5 *(I3+I1-I2) );
		double c = Math.sqrt( 2.5 *(I1+I2-I3) );
		double[] semiaxes = new double[] {a, b, c};
		
		double[][] uvectors = new double[3][3];
		for (int i = 0; i < eigenvalues.length; i++) {
			uvectors[i][0] = eigenvectors.get(0, i);
			uvectors[i][1] = eigenvectors.get(1, i);
			uvectors[i][2] = eigenvectors.get(2, i);
		}
			
		// Store in the Spot object
		double theta, phi;
		for (int i = 0; i < uvectors.length; i++) {
			theta = Math.acos( uvectors[i][2] / Math.sqrt(
					uvectors[i][0]*uvectors[i][0] +
					uvectors[i][1]*uvectors[i][1] +
					uvectors[i][2]*uvectors[i][2]) );
			phi = Math.atan2(uvectors[i][1], uvectors[i][0]);
			if (phi < - Math.PI/2 ) 
				phi += Math.PI; // For an ellipsoid we care only for the angles in [-pi/2 , pi/2]
			if (phi > Math.PI/2 ) 
				phi -= Math.PI; 
			
			spot.putFeature(featurelist_sa[i], (float) semiaxes[i]);
			spot.putFeature(featurelist_phi[i], (float) phi);
			spot.putFeature(featurelist_theta[i], (float) theta);
		}
		
	}
	
	
	
	public static void main(String[] args) {

		// Parameters
		int size_x = 200;
		int size_y = 200;
		int size_z = 200;
		
		float a = 10;
		float b = 5;
		float c = 5;
		float theta_r = (float) Math.toRadians(0); // I am unable to have it working for theta_r != 0
		float phi_r = (float) Math.toRadians(45);

		float max_radius = Math.max(a, Math.max(b, c));
		float[] calibration = new float[] {1, 1, 1};
		
		// Create blank image
		Image<UnsignedByteType> img = new ImageFactory<UnsignedByteType>(
				new UnsignedByteType(),
				new ArrayContainerFactory()
			).createImage(new int[] {200, 200, 200});
		final byte on = (byte) 255;
		
		// Create an ellipse 
		long start = System.currentTimeMillis();
		System.out.println(String.format("Creating an ellipse with a = %.1f, b = %.1f, c = %.1f", a, b, c));
		System.out.println(String.format("phi = %.1f and theta = %.1f", Math.toDegrees(phi_r), Math.toDegrees(theta_r)));
		float[] center = new float[] { size_x/2, size_y/2, size_z/2 };
		SphereCursor<UnsignedByteType> sc = new SphereCursor<UnsignedByteType>(img, center, max_radius, calibration);
		double r2, theta, phi, term;
		double cosphi, sinphi, costheta, sintheta;
		while (sc.hasNext()) {
			sc.fwd();
			r2 = sc.getDistanceSquared();
			phi = sc.getPhi();
			theta = sc.getTheta();
			cosphi = Math.cos(phi-phi_r);
			sinphi = Math.sin(phi-phi_r);
			costheta = Math.cos(theta-theta_r);
			sintheta = Math.sin(theta-theta_r);
			term = r2*cosphi*cosphi*sintheta*sintheta/a/a +
				r2*sinphi*sinphi*sintheta*sintheta/b/b   +
				r2*costheta*costheta/c/c;
			if (term <= 1) 
				sc.getType().set(on);
		}
		sc.close();
		long end = System.currentTimeMillis();
		System.out.println("Ellipse creation done in " + (end-start) + " ms.");
		System.out.println();
		
		ij.ImageJ.main(args);
		img.getDisplay().setMinMax();
		ImageJFunctions.copyToImagePlus(img).show();
		
		start = System.currentTimeMillis();
		BlobMorphology<UnsignedByteType> bm = new BlobMorphology<UnsignedByteType>(img, 2*max_radius, calibration);
		Spot spot = new Spot(center);
		bm.process(spot);
		end = System.currentTimeMillis();
		System.out.println("Blob morphology analyzed in " + (end-start) + " ms.");
		float phiv, thetav, lv;
		for (int j = 0; j < 3; j++) {
			lv = spot.getFeature(featurelist_sa[j]);
			phiv = spot.getFeature(featurelist_phi[j]);
			thetav = spot.getFeature(featurelist_theta[j]);
			System.out.println(String.format("For axis of semi-length %.1f, orientation is phi = %.1f°, theta = %.1f°",
					lv, Math.toDegrees(phiv), Math.toDegrees(thetav)));
		}
	}

}
