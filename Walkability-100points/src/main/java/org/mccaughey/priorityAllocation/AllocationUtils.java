package org.mccaughey.priorityAllocation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geotools.data.DataUtilities;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.filter.text.cql2.CQLException;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;

public class AllocationUtils {

  static final Logger LOGGER = LoggerFactory.getLogger(AllocationUtils.class);

  public static SimpleFeatureCollection intersection(SimpleFeatureSource featuresOfInterest,
      SimpleFeature intersectingFeature) throws IOException {
    SimpleFeatureCollection features = featuresOfInterest.getFeatures();
    FilterFactory2 ff = CommonFactoryFinder.getFilterFactory2();
    String geometryPropertyName = features.getSchema().getGeometryDescriptor().getLocalName();

    Filter filter = ff.intersects(ff.property(geometryPropertyName),
        ff.literal(intersectingFeature.getDefaultGeometry()));

    // return features.subCollection(filter); <-- THIS IS REALLY SLOW
    return featuresOfInterest.getFeatures(filter); // <-- DO THIS INSTEAD

  }

  public static SimpleFeatureCollection prioritiseOverlap(SimpleFeatureCollection parcels, String categoryAttribute,
      Map<String, Integer> priorityOrder) {
    SimpleFeatureIterator parcelIterator = parcels.features();
    SimpleFeatureSource parcelSource = DataUtilities.source(parcels);
    Map<String, SimpleFeature> uniqueParcels = new HashMap();
    try {
      while (parcelIterator.hasNext()) {
        SimpleFeature parcel = parcelIterator.next();
        LOGGER.debug("Prioritising parcel {}", parcel.getID());
        SimpleFeatureCollection intersectingParcels = intersection(parcelSource, parcel);

        if (!(uniqueParcels.containsKey(parcel.getID()))) {
          uniqueParcels.put(parcel.getID(), parcel);
        }
        SimpleFeatureIterator intersectingParcelsIter = intersectingParcels.features();
        while (intersectingParcelsIter.hasNext()) {
          SimpleFeature intersectingParcel = intersectingParcelsIter.next();
          if (!(intersectingParcel.getID().equals(parcel.getID()))) {
            {
              String parcelCategory = (String) parcel.getAttribute(categoryAttribute);
              String intersectingParcelCategory = (String) intersectingParcel.getAttribute(categoryAttribute);
              if ((priorityOrder.get(parcelCategory) != null)
                  && (priorityOrder.get(intersectingParcelCategory) != null)) {
                int parcelPriority = priorityOrder.get(parcel.getAttribute(categoryAttribute));;
                int intersectingPriority = priorityOrder.get(intersectingParcel.getAttribute(categoryAttribute));
                if (parcelPriority > intersectingPriority) { // -->intersectingParcel
                  // is
                  // more
                  // important
                  Geometry parcelGeometry = (Geometry) uniqueParcels.get(parcel.getID()).getDefaultGeometry();
                  Geometry intersectingGeometry = (Geometry) intersectingParcel.getDefaultGeometry();
                  Geometry parcelDifference = parcelGeometry.difference(intersectingGeometry);
                  SimpleFeature newFeature = buildFeatureFromGeometry(parcel, parcelDifference);
                  uniqueParcels.put(parcel.getID(), newFeature);
                }
              }
            }
          }
        }
      }
    } catch (IOException e) {
      LOGGER.error("Failed at performing overlap removal process");
    } finally {
      parcelIterator.close();
    }
    Collection<SimpleFeature> features = uniqueParcels.values();
    return DataUtilities.collection(new ArrayList(features));
  }

  public static List<SimpleFeature> dissolveByCategory(SimpleFeatureCollection parcels, String categoryAttribute,
      Set<String> uniqueClassifications) throws IOException, CQLException {

    List<SimpleFeature> dissolved = new ArrayList<SimpleFeature>();
    try {
      SimpleFeatureSource parcelsSource = DataUtilities.source(parcels);
      for (String classification : uniqueClassifications) {
        Filter filter = CQL.toFilter(categoryAttribute + "=" + classification);
        SimpleFeatureCollection categoryCollection = parcelsSource.getFeatures(filter);
        if (categoryCollection.size() > 0) {
          List<SimpleFeature> sc = dissolve(categoryCollection);
          dissolved.addAll(sc);
        }
      }
      return dissolved;
    } catch (IOException e1) {
      throw new IOException("Failed dissolve by category process", e1);
    }
  }

  public static List<SimpleFeature> dissolve(SimpleFeatureCollection collection) throws IOException {
    FeatureIterator<SimpleFeature> features = collection.features();
    List<SimpleFeature> dissolvedFeatures = new ArrayList<SimpleFeature>();
    try {
      List<Geometry> geometries = new ArrayList<Geometry>();
      SimpleFeature feature = null;
      while (features.hasNext()) {
        feature = features.next();
        geometries.add((Geometry) feature.getDefaultGeometry());
      }
      Geometry dissolved = union(geometries);
      for (int n = 0; n < dissolved.getNumGeometries(); n++) {
        Geometry split = dissolved.getGeometryN(n);
        SimpleFeature splitFeature = buildFeatureFromGeometry(feature, feature.getFeatureType(), split,
            new ArrayList<String>(), "id." + collection.hashCode() + "." + n);
        dissolvedFeatures.add(splitFeature);
      }
      return dissolvedFeatures;

    } finally {
      features.close();
    }

  }

  public static SimpleFeature buildFeatureFromGeometry(SimpleFeature baseFeature, Geometry geom) {
    SimpleFeatureBuilder sfb = new SimpleFeatureBuilder(baseFeature.getFeatureType());
    sfb.addAll(baseFeature.getAttributes());
    sfb.set(sfb.getFeatureType().getGeometryDescriptor().getLocalName(), geom);
    return sfb.buildFeature(baseFeature.getID());
  }

  public static SimpleFeature buildFeatureFromGeometry(SimpleFeature baseFeature, SimpleFeatureType newFT,
      Geometry geom, List<String> newValues, String id) {
    SimpleFeatureBuilder sfb = new SimpleFeatureBuilder(newFT);
    sfb.addAll(baseFeature.getAttributes());
    sfb.set(sfb.getFeatureType().getGeometryDescriptor().getLocalName(), geom);
    for (String value : newValues) {
      sfb.add(value);
    }
    return sfb.buildFeature(id);
  }

  private static Geometry union(List<Geometry> geometries) {
    Geometry[] geom = new Geometry[geometries.size()];
    geometries.toArray(geom);
    GeometryFactory fact = geom[0].getFactory();
    Geometry geomColl = fact.createGeometryCollection(geom);
    Geometry union = geomColl.union();
    return union;
  }

  static SimpleFeature buildFeature(SimpleFeatureType ft, SimpleFeature baseFeature, List<String> newValues) {
    SimpleFeatureBuilder sfb = new SimpleFeatureBuilder(ft);
    sfb.addAll(baseFeature.getAttributes());
    for (String value : newValues) {
      sfb.add(value);
    }
    return sfb.buildFeature(baseFeature.getID());
  }

  public static Map<String, String> createLanduseLookup(SimpleFeatureSource lookupSource, String keyColumn,
      String valueColumn) {
    SimpleFeatureIterator lookupFeatures;
    Map<String, String> lookupTable = new HashMap<String, String>();
    try {
      lookupFeatures = lookupSource.getFeatures().features();
      while (lookupFeatures.hasNext()) {
        SimpleFeature lookupFeature = lookupFeatures.next();
        lookupTable.put((String) lookupFeature.getAttribute(keyColumn),
            (String) lookupFeature.getAttribute(valueColumn));
      }
    } catch (IOException e) {
      LOGGER.error("Failed to read SimpleFeaturSource input");
    }
    return lookupTable;
  }
}
