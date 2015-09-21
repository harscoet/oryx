/*
 * Copyright (c) 2014, Cloudera and Intel, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.app.serving.als;

import java.util.List;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.PathSegment;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

import com.cloudera.oryx.app.als.ALSUtils;
import com.cloudera.oryx.app.serving.CSVMessageBodyWriter;
import com.cloudera.oryx.app.serving.OryxServingException;
import com.cloudera.oryx.app.serving.als.model.ALSServingModel;
import com.cloudera.oryx.common.collection.Pair;
import com.cloudera.oryx.common.math.VectorMath;

/**
 * <p>Responds to a GET request to
 * {@code /estimateForAnonymous/[toItemID]/[itemID1(=value1)](/[itemID2(=value2)]/...)}.
 * That is, 1 or more item IDs are supplied, which may each optionally correspond to
 * a value or else default to 1.</p>
 *
 * <p>Unknown item IDs are ignored.</p>
 *
 * <p>Outputs the result of the method call as a value on one line.</p>
 */
@Singleton
@Path("/estimateForAnonymous")
public final class EstimateForAnonymous extends AbstractALSResource {

  @GET
  @Path("{toItemID}/{itemID : .+}")
  @Produces({MediaType.TEXT_PLAIN, CSVMessageBodyWriter.TEXT_CSV, MediaType.APPLICATION_JSON})
  public Double get(
      @PathParam("toItemID") String toItemID,
      @PathParam("itemID") List<PathSegment> pathSegments) throws OryxServingException {

    ALSServingModel model = getALSServingModel();
    float[] toItemVector = model.getItemVector(toItemID);
    checkExists(toItemVector != null, toItemID);

    double[] anonymousUserFeatures = buildAnonymousUserFeatures(model, pathSegments);
    return VectorMath.dot(anonymousUserFeatures, toItemVector);
  }

  static double[] buildAnonymousUserFeatures(ALSServingModel model,
                                             List<PathSegment> pathSegments) {
    List<Pair<String,Double>> itemValuePairs = parsePathSegments(pathSegments);
    boolean implicit = model.isImplicit();
    int features = model.getFeatures();

    double[] QuiYi = new double[features];
    for (Pair<String,Double> itemValue : itemValuePairs) {
      float[] itemVector = model.getItemVector(itemValue.getFirst());
      if (itemVector != null) {
        // Given value is taken to be the fictitious current value of Qui = Xu * Yi^T
        double Qui = itemValue.getSecond();
        // Qui' is the target, new value of Qui
        double targetQui = ALSUtils.computeTargetQui(implicit, Qui, 0.5); // 0.5 reflects a "don't know" state
        // We're constructing a row Xu for a fictional user u such that Qu = Xu * Y^T
        // This is solved as Qu * Y = Xu * (Y^T * Y) for Xu.
        // Qu is all zeroes except that it has values Qui in position i for several i.
        // Qu * Y is really just Qui * Yi, summed up over i.
        for (int i = 0; i < features; i++) {
          QuiYi[i] += targetQui * itemVector[i];
        }
      }
    }
    return model.getYTYSolver().solveDToD(QuiYi);
  }

  static List<Pair<String, Double>> parsePathSegments(List<PathSegment> pathSegments) {
    return Lists.transform(pathSegments,
        new Function<PathSegment, Pair<String, Double>>() {
          @Override
          public Pair<String, Double> apply(PathSegment segment) {
            String s = segment.getPath();
            int offset = s.indexOf('=');
            return offset < 0 ?
                new Pair<>(s, 1.0) :
                new Pair<>(s.substring(0, offset),
                    Double.parseDouble(s.substring(offset + 1)));
          }
        });
  }

}