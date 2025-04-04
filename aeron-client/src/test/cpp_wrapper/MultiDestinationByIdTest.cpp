
/*
 * Copyright 2014-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <functional>

#include <gtest/gtest.h>
#include <gmock/gmock.h>

#include "EmbeddedMediaDriver.h"
#include "Aeron.h"
#include "ChannelUriStringBuilder.h"
#include "TestUtil.h"

using namespace aeron;
using testing::MockFunction;
using testing::_;

#define PUB_MDC_MANUAL_URI "aeron:udp?control-mode=manual|tags=3,4"
#define SUB1_MDC_MANUAL_URI "aeron:udp?endpoint=localhost:24326|group=true"
#define SUB2_MDC_MANUAL_URI "aeron:udp?endpoint=localhost:24327|group=true"

#define UNICAST_ENDPOINT_A "localhost:24325"
#define UNICAST_ENDPOINT_B "localhost:24326"
#define SUB_URI "aeron:udp?control-mode=manual"

#define streamId INT32_C(1001)

class MultiDestinationByIdTest : public testing::TestWithParam<std::tuple<const char *, const char *>>
{
public:
    MultiDestinationByIdTest()
    {
        m_driver.start();
    }

    ~MultiDestinationByIdTest() override
    {
        m_driver.stop();
    }

protected:
    void SetUp() override
    {
        Context ctx;
        ctx.useConductorAgentInvoker(true);
        m_aeron = Aeron::connect(ctx);
    }

    void TearDown() override
    {
        invoker().invoke();
        m_aeron = nullptr;
    }

    AgentInvoker<ClientConductor> invoker()
    {
        return m_aeron->conductorAgentInvoker();
    }

protected:
    EmbeddedMediaDriver m_driver;
    fragment_handler_t m_noOpHandler =
        [&](concurrent::AtomicBuffer &b, util::index_t offset, util::index_t length, Header &header) {};
    std::shared_ptr<Aeron> m_aeron;
    std::array<std::uint8_t, 1024> buf = {};
    AtomicBuffer buffer{buf};

};

typedef std::array<std::uint8_t, 1024> buffer_t;

TEST_F(MultiDestinationByIdTest, shouldAddRemoveDestinationFromPublicationById)
{
    auto sub1RegId = m_aeron->addSubscription(SUB1_MDC_MANUAL_URI, streamId);
    auto sub2RegId = m_aeron->addSubscription(SUB2_MDC_MANUAL_URI, streamId);
    auto pubRegId = m_aeron->addPublication(PUB_MDC_MANUAL_URI, streamId);

    POLL_FOR_NON_NULL(sub1, m_aeron->findSubscription(sub1RegId), invoker());
    POLL_FOR_NON_NULL(sub2, m_aeron->findSubscription(sub2RegId), invoker());
    POLL_FOR_NON_NULL(pub, m_aeron->findPublication(pubRegId), invoker());

    std::int64_t dest1CorrelationId = pub->addDestination(SUB1_MDC_MANUAL_URI);
    std::int64_t dest2CorrelationId = pub->addDestination(SUB2_MDC_MANUAL_URI);

    POLL_FOR(pub->findDestinationResponse(dest1CorrelationId), invoker());
    POLL_FOR(pub->findDestinationResponse(dest2CorrelationId), invoker());

    POLL_FOR(sub1->isConnected(), invoker());
    POLL_FOR(sub2->isConnected(), invoker());

    POLL_FOR(0 < pub->offer(buffer, 0, 128), invoker());

    POLL_FOR(0 < sub1->poll(m_noOpHandler, 1), invoker());

    POLL_FOR(0 < sub2->poll(m_noOpHandler, 1), invoker());

    int64_t removeDestCorrelationId = pub->removeDestination(dest1CorrelationId);

    POLL_FOR(pub->findDestinationResponse(removeDestCorrelationId), invoker());

    POLL_FOR(0 < pub->offer(buffer, 0, 128), invoker());
    POLL_FOR(0 < sub2->poll(m_noOpHandler, 1), invoker());

    EXPECT_EQ(0, sub2->poll(m_noOpHandler, 1));
}

TEST_F(MultiDestinationByIdTest, shouldAddRemoveDestinationFromExclusivePublicationById)
{
    auto sub1RegId = m_aeron->addSubscription(SUB1_MDC_MANUAL_URI, streamId);
    auto sub2RegId = m_aeron->addSubscription(SUB2_MDC_MANUAL_URI, streamId);
    auto pubRegId = m_aeron->addExclusivePublication(PUB_MDC_MANUAL_URI, streamId);

    POLL_FOR_NON_NULL(sub1, m_aeron->findSubscription(sub1RegId), invoker());
    POLL_FOR_NON_NULL(sub2, m_aeron->findSubscription(sub2RegId), invoker());
    POLL_FOR_NON_NULL(pub, m_aeron->findExclusivePublication(pubRegId), invoker());

    std::int64_t dest1CorrelationId = pub->addDestination(SUB1_MDC_MANUAL_URI);
    std::int64_t dest2CorrelationId = pub->addDestination(SUB2_MDC_MANUAL_URI);

    POLL_FOR(pub->findDestinationResponse(dest1CorrelationId), invoker());
    POLL_FOR(pub->findDestinationResponse(dest2CorrelationId), invoker());

    POLL_FOR(sub1->isConnected(), invoker());
    POLL_FOR(sub2->isConnected(), invoker());

    POLL_FOR(0 < pub->offer(buffer, 0, 128), invoker());

    POLL_FOR(0 < sub1->poll(m_noOpHandler, 1), invoker());

    POLL_FOR(0 < sub2->poll(m_noOpHandler, 1), invoker());

    int64_t removeDestCorrelationId = pub->removeDestination(dest1CorrelationId);

    POLL_FOR(pub->findDestinationResponse(removeDestCorrelationId), invoker());

    POLL_FOR(0 < pub->offer(buffer, 0, 128), invoker());
    POLL_FOR(0 < sub2->poll(m_noOpHandler, 1), invoker());

    EXPECT_EQ(0, sub2->poll(m_noOpHandler, 1));
}
