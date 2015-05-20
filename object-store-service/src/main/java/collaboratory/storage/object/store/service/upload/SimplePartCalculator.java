/*
 * Copyright (c) 2015 The Ontario Institute for Cancer Research. All rights reserved.                             
 *                                                                                                               
 * This program and the accompanying materials are made available under the terms of the GNU Public License v3.0.
 * You should have received a copy of the GNU General Public License along with                                  
 * this program. If not, see <http://www.gnu.org/licenses/>.                                                     
 *                                                                                                               
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY                           
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES                          
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT                           
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,                                
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED                          
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;                               
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER                              
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN                         
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package collaboratory.storage.object.store.service.upload;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import collaboratory.storage.object.store.core.model.Part;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

/**
 * a simple way to divide an object into multi parts
 */
@Slf4j
public class SimplePartCalculator implements ObjectPartCalculator {

  private static final int MAX_NUM_PART = 10000;
  private static final int MIN_PART_SIZE = 20 * 1024 * 1024; // 20MB

  private final int minPartSize;

  public SimplePartCalculator(int minPartSize) {
    this.minPartSize = Math.max(minPartSize, MIN_PART_SIZE);
  }

  @Override
  public List<Part> divide(long fileSize) {
    int defaultPartSize = Math.max(minPartSize, (int) (fileSize / MAX_NUM_PART) + 1);
    log.debug("Part Size: {}", defaultPartSize);
    long filePosition = 0;
    Builder<Part> parts = ImmutableList.builder();
    for (int i = 1; filePosition < fileSize; ++i) {
      int partSize = (int) Math.min(defaultPartSize, fileSize - filePosition);
      parts.add(new Part(i, partSize, filePosition, null, null));
      filePosition += partSize;
    }
    return parts.build();
  }
}